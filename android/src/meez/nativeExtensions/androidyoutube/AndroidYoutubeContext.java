package meez.nativeExtensions.androidyoutube;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import android.annotation.TargetApi;
import android.app.Dialog;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.*;
import android.widget.FrameLayout;
import com.adobe.fre.FREContext;
import com.adobe.fre.FREFunction;
import com.adobe.fre.FREObject;
import com.google.android.youtube.player.YouTubeInitializationResult;
import com.google.android.youtube.player.YouTubePlayer;
import com.google.android.youtube.player.YouTubePlayerFragment;
import org.json.JSONObject;

@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public class AndroidYoutubeContext extends FREContext implements YouTubePlayer.OnInitializedListener, YouTubePlayer.PlayerStateChangeListener, YouTubePlayer.PlaybackEventListener
{
    // Definitions

    /** States */
    private enum State
    {
        UNSTARTED,
        PLAYING,
        STOPPED
    }

    /** Player States */
    public static final String PLAYER_STATE_PLAYING="playing";
    public static final String PLAYER_STATE_UNSTARTED="unstarted";
    public static final String PLAYER_STATE_ENDED="ended";
    public static final String PLAYER_STATE_PAUSED="paused";
    public static final String PLAYER_STATE_BUFFERING="buffering";
    public static final String PLAYER_STATE_CUED="cued";
    public static final String PLAYER_STATE_UNKNOWN="unknown";

    /** Player Errors */
    public static final String PLAYER_ERROR_INVALID="invalid";
    public static final String PLAYER_ERROR_HTML5="html5";
    public static final String PLAYER_ERROR_NOT_FOUND="not found";
    public static final String PLAYER_ERROR_EMBED="cannot embed";
    public static final String PLAYER_ERROR_NETWORK="network error";
    public static final String PLAYER_ERROR_TOO_SMALL="player view too small";
    public static final String PLAYER_ERROR_OVERLAID="unauthorized overlay";

    /** YouTube Player Fragment */
    private YouTubePlayerFragment playerFragment;

    /** YouTube Player */
    private YouTubePlayer player;

    /** Dialog Box to hold video player view */
    private Dialog dialog;

    /** Layout Parameters */
    private WindowManager.LayoutParams layoutParams;

    /** Video Time Timer */
    private VideoTimer videoTimer;

    /** Video Container */
    private FrameLayout videoContainer;

    /** Reason Video player is unsupported */
    private String unsupportedReason;

    /** Current State */
    private State state;

    // temp vars to hold onto until player is created

    /** URL/VideoId to load in Player */
    private String videoToPlay;

    /** Video Start time */
    private int startTime = 0;

    /** Create a new AndroidYoutubeContext */
    public AndroidYoutubeContext()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext()");
        this.unsupportedReason="";
        changeState(State.UNSTARTED);
    }

    /** Dispose */
    @Override
    public void dispose()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.dispose()");

        changeState(State.STOPPED);

        Extension.context = null;

        stopUpdateTimer();
        this.videoTimer=null;

        try
        {
            if (this.dialog!=null)
            {
                this.dialog.dismiss();
            }
        }
        catch(Throwable t)
        {
            Log.w(Extension.TAG, "Could not dismiss video dialog", t);
        }
        finally
        {
            this.dialog=null;
        }

        try
        {
            this.playerFragment.setRetainInstance(false);

            // Removing fragment will call onDestroyView() on the fragment, which will release the YouTubePlayer
            // see https://developers.google.com/youtube/android/player/reference/com/google/android/youtube/player/YouTubePlayerFragment#Overview
            getActivity().getFragmentManager()
                    .beginTransaction()
                    .remove(this.playerFragment)
                    .commit();
        }
        catch (Throwable t)
        {
            Log.w(Extension.TAG, "Error removing player fragment", t);
        }
        finally
        {
            this.playerFragment=null;
        }
    }

    /** Registers AS function name to Java Function Class */
    @Override
    public Map<String, FREFunction> getFunctions()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.getFunctions");

        Map<String, FREFunction> functionMap = new HashMap<String, FREFunction>();

        functionMap.put("loadURL",              new LoadURLFunction());
        functionMap.put("sendJSON",             new SendJSONFunction());
        functionMap.put("setFrame",             new SetFrameFunction());
        functionMap.put("initVideo",            new InitVideoFunction());
        functionMap.put("isSupported",          new IsSupportedFunction());
        functionMap.put("getUnsupportedReason", new GetUnsupportedReasonFunction());

        return functionMap;
    }

    /**
     * Initialize the video player
     * @param devKey Developer key from google/youtube
     */
    public void initVideo(String devKey)
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.initVid()");

        //Hack - do not want to see video container until it has been added to dialog
        getVideoContainer().setVisibility(View.INVISIBLE);

        //Hack - videoContainer is only added to view long enough to attach fragment.
        // In Fragment's onViewCreated() videoContainer is removed from root container and set as view of Dialog
        getRootContainer().addView(getVideoContainer());
        getVideoContainer().setId(AndroidYoutubeContext.generateViewId());
        getActivity().getFragmentManager().beginTransaction()
                .add(getVideoContainer().getId(), getPlayerFragment())
                .commit();

        // Initialize the video player
        getPlayerFragment().initialize(devKey, this);
    }

    // Views

    /** Get Root Container ViewGroup */
    public ViewGroup getRootContainer()
    {
        return (ViewGroup)((ViewGroup)getActivity().findViewById(android.R.id.content)).getChildAt(0);
    }

    /** Get the video container layout */
    public FrameLayout getVideoContainer()
    {
        if (this.videoContainer==null)
        {
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
            this.videoContainer = new FrameLayout(getActivity());
            this.videoContainer.setLayoutParams(params);
        }
        return this.videoContainer;
    }

    /** Get the YouTube Player Fragment */
    public YouTubePlayerFragment getPlayerFragment()
    {
        if (this.playerFragment==null)
        {
            this.playerFragment = new AndroidYoutubeContext.CustomPlayerFragment();
            this.playerFragment.setRetainInstance(true);
        }
        return this.playerFragment;
    }

    /** Get the layout params */
    public WindowManager.LayoutParams getLayoutParams()
    {
        if (this.layoutParams==null)
        {
            this.layoutParams=new WindowManager.LayoutParams();
            this.layoutParams.gravity = Gravity.TOP | Gravity.LEFT;
            this.layoutParams.dimAmount = 0.0f;
            this.layoutParams.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                    WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        }
        return this.layoutParams;
    }

    /** Get the Dialog box */
    public Dialog getDialog()
    {
        if (this.dialog==null)
        {
            this.dialog = new Dialog(getActivity());
            this.dialog.setCancelable(false);
            this.dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            this.dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            this.dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        return this.dialog;
    }

    // Public methods

    /** Play video by ID (YouTube video id) */
    public void playVideoById(String videoId, double startTime)
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.playVideoById("+videoId+")");

        // seconds to ms
        int startTimeMS = (int)(1000 * startTime);
        if (playerIsReady())
        {
            this.player.loadVideo(videoId, startTimeMS);
        }
        else
        {
            this.videoToPlay = videoId;
            this.startTime = startTimeMS;
        }
    }

    /** Display the Dialog with the Video player */
    public void showVideoDialog()
    {
        // hide video until explicitly positioned by Actionscript call
        setVideoFrame(-5000, -5000, 600, 400);

        getDialog().setContentView(getVideoContainer());

        getDialog().show();
    }

    /** Play Video */
    public  void playVideo()
    {
        this.player.play();
    }

    /** Pause Video */
    public void pauseVideo()
    {
        this.player.pause();
    }

    /** Seek in Video */
    public void doSeek(double timeInSeconds)
    {
        int timeInMilliseconds = (int)(1000 * timeInSeconds);
        int dur = this.player.getDurationMillis();

        // prevent seeking past video end
        if (dur>0 && timeInMilliseconds>=dur)
        {
            // go back 1 second from video end
            timeInMilliseconds = Math.max(0, dur-1000);
        }
        this.player.seekToMillis(timeInMilliseconds);
    }

    /** Set video frame */
    public void setVideoFrame(int x, int y, int width, int height)
    {
        Log.d(Extension.TAG, String.format("AndroidYouTubeContext.setVideoFrame(%d, %d, %d, %d)", x, y, width, height));

        getLayoutParams().width = width;
        getLayoutParams().height = height;
        getLayoutParams().x = x;
        getLayoutParams().y = y;

        getDialog().getWindow().setAttributes(getLayoutParams());
    }

    /** Get the reason YouTube video is unsupported */
    public String getUnsupportedReason()
    {
        return this.unsupportedReason;
    }

    // Messaging

    /** On message (received from Actionscript) */
    public void onMessage(String json) throws Exception
    {
        JSONObject msgObj = new JSONObject(json);
        String action = msgObj.getString("action");

        Log.d(Extension.TAG, "onMessageReceived("+action+")");

        if (action.equals("play"))
        {
            playVideo();
        }

        else if(action.equals("playById"))
        {
            playVideoById(msgObj.getString("videoId"), msgObj.getDouble("startTime"));
        }

        else if(action.equals("stop"))
        {
            pauseVideo();
        }

        else if(action.equals("pause"))
        {
            pauseVideo();
        }

        else if(action.equals("seek"))
        {
            doSeek(msgObj.getDouble("time"));
        }

        else if(action.equals("dispose"))
        {
            Log.d(Extension.TAG, "dispose called in onMessage()");
            // Do nothing - dispose() will be called in Actionscript from context.dispose;
        }

        else
        {
            Log.w(Extension.TAG, "Unexpected message type received from actionscript: ("+json+")");
        }

    }

    // YouTubePlayer.OnInitializedListener Implementation

    @Override
    public void onInitializationFailure(YouTubePlayer.Provider provider, YouTubeInitializationResult errorReason)
    {
        Log.w(Extension.TAG, "AndroidYouTubeContext.onInitializaionFailure(). Could not Initialize player.");

        dispatchEventWithReason("videoError", "Could not initialize video player");
    }

    @Override
    public void onInitializationSuccess(YouTubePlayer.Provider provider, YouTubePlayer player, boolean wasRestored)
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.onInitSuccess (player="+player+", wasRestored="+wasRestored+")");

        this.player=player;
        this.player.setPlayerStateChangeListener(this);
        this.player.setPlaybackEventListener(this);

        if (!wasRestored)
        {
            // no video player controls
            this.player.setPlayerStyle(YouTubePlayer.PlayerStyle.CHROMELESS);

            if (this.videoToPlay!=null)
            {
                this.player.loadVideo(this.videoToPlay, this.startTime);
            }
            this.videoToPlay = null;
            this.startTime = 0;
        }

        sendData("playerReady");
    }

    //  YouTubePlayer.PlayerStateChangeListener Implementation

    @Override
    public void onAdStarted()
    {
        Log.d(Extension.TAG, "Ad Started");
        sendData("Ad started");
    }

    @Override
    public void onError(YouTubePlayer.ErrorReason errorReason)
    {
        Log.w(Extension.TAG, "AndroidYouTubeContext.onError() error: ("+errorReason.toString()+")");

        String msg;
        int code;

        // 1st two are not counted as errors, but Actionscript player needs to be notified
        if (errorReason==YouTubePlayer.ErrorReason.UNAUTHORIZED_OVERLAY)
        {
            msg=PLAYER_ERROR_OVERLAID;
            code=0;
        }
        else if (errorReason==YouTubePlayer.ErrorReason.PLAYER_VIEW_TOO_SMALL)
        {
            msg=PLAYER_ERROR_TOO_SMALL;
            code=0;
        }

        else if (errorReason==YouTubePlayer.ErrorReason.NOT_PLAYABLE ||
                 errorReason==YouTubePlayer.ErrorReason.AUTOPLAY_DISABLED ||
                 errorReason==YouTubePlayer.ErrorReason.INTERNAL_ERROR)
        {
            msg=PLAYER_ERROR_INVALID;
            code=2;
        }

        else if (errorReason==YouTubePlayer.ErrorReason.EMPTY_PLAYLIST)
        {
            msg=PLAYER_ERROR_NOT_FOUND;
            code=100;
        }

        else if (errorReason==YouTubePlayer.ErrorReason.USER_DECLINED_RESTRICTED_CONTENT ||
                 errorReason==YouTubePlayer.ErrorReason.USER_DECLINED_HIGH_BANDWIDTH)
        {
            msg=PLAYER_ERROR_EMBED;
            code=101;
        }
        else if (errorReason==YouTubePlayer.ErrorReason.NETWORK_ERROR ||
                 errorReason==YouTubePlayer.ErrorReason.UNEXPECTED_SERVICE_DISCONNECTION)
        {
            msg=PLAYER_ERROR_NETWORK;
            code=500; // Meez API Error Code
        }
        else
        {
            msg=errorReason.toString();
            code=500; // Meez API Error Code
        }
        sendError(code, msg);
    }

    @Override
    public void onLoaded(String arg0)
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.onLoaded");

        sendState(PLAYER_STATE_UNSTARTED);
    }

    @Override
    public void onLoading()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.onLoading");

        sendState(PLAYER_STATE_BUFFERING);
    }

    @Override
    public void onVideoEnded()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.onVideoEnded");

        sendState(PLAYER_STATE_ENDED);
    }

    @Override
    public void onVideoStarted()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.onVideoStarted");
        // Nothing to send
    }

    // YouTubePlayer.PlaybackEventListener Implementation

    @Override
    public void onBuffering(boolean isBuffering)
    {
        if (isBuffering)
        {
            sendState(PLAYER_STATE_BUFFERING);
        }
    }

    @Override
    public void onPaused()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.onPaused");

        changeState(State.STOPPED);
        stopUpdateTimer();
        sendState(PLAYER_STATE_PAUSED);
    }

    @Override
    public void onPlaying()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.onPlaying");

        changeState(State.PLAYING);
        startUpdateTimer();
        sendState(PLAYER_STATE_PLAYING);
    }

    @Override
    public void onSeekTo(int newPositionMillis)
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.onSeekTo("+Integer.toString(newPositionMillis)+")");
    }

    @Override
    public void onStopped()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.onStopped");

        changeState(State.STOPPED);
        stopUpdateTimer();
    }

    // Implementation

    /** Callable on View Interface */
    public interface CallableOnView
    {
        void call(View view);
    }

    /** Set Save Enabled on Video view and its children */
    public void setVideoSaveEnabled(final boolean value)
    {
        recursiveCallOnView(getVideoContainer(), new CallableOnView(){
            @Override public void call(View view) {
                view.setSaveEnabled(value);
            }
        });
    }

    /** Recursively call action on View */
    protected void recursiveCallOnView(View view, CallableOnView callableOnView)
    {
        if (view==null)
            return;

        if (view instanceof ViewGroup)
        {
            for (int i=0; i < ((ViewGroup) view).getChildCount(); i++)
            {
                recursiveCallOnView(((ViewGroup) view).getChildAt(i), callableOnView);
            }
        }
        callableOnView.call(view);
    }

    /** Initialize the Dialog box to show video */
    private void initVideoDialog()
    {
        // called in onCreateView method of custom player fragment
        getRootContainer().removeView(getVideoContainer());
        showVideoDialog();
        getVideoContainer().setVisibility(View.VISIBLE);
    }

    /** Start the update timer */
    private void startUpdateTimer()
    {
        if (this.videoTimer==null)
        {
            this.videoTimer = new VideoTimer(1000L);
        }
        this.videoTimer.start();
    }

    /** Stop the update timer */
    private void stopUpdateTimer()
    {
        if (this.videoTimer!=null)
        {
            this.videoTimer.stop();
        }
    }

    /** Player is ready */
    private boolean playerIsReady()
    {
        return this.player!=null;
    }

    /** Change state */
    private State changeState(State newState)
    {
        State oldState = this.state;
        this.state = newState;
        return oldState;
    }

    /** Check the current state */
    private boolean checkState(State state)
    {
        return this.state.equals(state);
    }

    // Event dispatching

    /** Send JSON encoded Message (to Actionscript) */
    public void sendMessage(JSONObject msg)
    {
        String msgString=msg.toString();
        dispatchEventWithReason("message", msgString);
    }

    /** Send generic data to Actionscript */
    private void sendData(String data)
    {
        //sendMessage({type:"data", data:data});
        try
        {
            JSONObject msgObj = new JSONObject();
            msgObj.put("type", "data");
            msgObj.put("data", data);
            sendMessage(msgObj);
        }
        catch (Throwable t)
        {
            Log.w(Extension.TAG, "Could not send data message ("+data+")", t);
        }
    }

    /** Send Time data to Actionscript */
    private void sendTimeData() throws Exception
    {
        // if not playing, send no data
        if (!checkState(State.PLAYING))
            return;

        //sendMessage({type:"time", time:{current:c, total:t}});
        double curSecs = (double)(this.player.getCurrentTimeMillis()/1000);
        double totalSecs = (double)(this.player.getDurationMillis()/1000);
        JSONObject timeObj = new JSONObject();
        timeObj.put("current",curSecs);
        timeObj.put("total", totalSecs);
        JSONObject msgObj = new JSONObject();
        msgObj.put("type", "time");
        msgObj.put("time", timeObj);

        sendMessage(msgObj);
    }

    /** Send player state to Actionscript */
    private void sendState(String state)
    {
        //sendMessage({type:"state", state:state});
        try
        {
            JSONObject msgObj = new JSONObject();
            msgObj.put("type", "state");
            msgObj.put("state", state);

            sendMessage(msgObj);
        }
        catch (Throwable t)
        {
            Log.w(Extension.TAG, "Could not send state message ("+state+")", t);
        }
    }

    /** Send Error to Actionscript */
    private void sendError(int code, String msg)
    {
        //sendMessage({type:"err", code:e.data, msg:msg})
        try
        {
            JSONObject msgObj = new JSONObject();
            msgObj.put("type", "err");
            msgObj.put("code", code);
            msgObj.put("msg", msg);
            sendMessage(msgObj);
        }
        catch (Throwable t)
        {
            Log.w(Extension.TAG, "Could not send Error message: ("+code+","+msg+")", t);
        }
    }

    /**
     * Dispatch event back to ANE Actionscript
     * @param type		type of Flash AndroidYouTubeEvent (e.g 'loadComplete') (@see AndroidYouTubeEvent)
     * @param reason	reason for event incl. event code if it exists (e.g. '1001 no fill')
     */
    public void dispatchEventWithReason(String type, String reason)
    {
        // Dispose has been called
        if (Extension.context==null)
        {
            Log.w(Extension.TAG, "Sending event to AIR App after dispose() called ("+type+", "+reason+")");
            return;
        }

        dispatchStatusEventAsync(type, reason);
    }

    // Helpers

    /** Get the Current SDK API level */
    public int getApiLevel()
    {
        int apiLevel = Build.VERSION.SDK_INT;

        Log.d(Extension.TAG, "API Level="+Integer.toString(apiLevel));
        if (apiLevel < 11)
        {
            this.unsupportedReason = "Android API Level less than 11 (" + Integer.toString(apiLevel) + ")";
        }

        return  apiLevel;
    }

    /** Determine if YouTube is installed or not */
    public boolean hasYouTubeInstalled()
    {
        try
        {
            ApplicationInfo info = getActivity().getPackageManager().getApplicationInfo("com.google.android.youtube", 0);
            Log.d(Extension.TAG, "YouTube App is installed");
            return true;
        }
        catch (PackageManager.NameNotFoundException e)
        {
            this.unsupportedReason = "YouTube App not installed";
            Log.w(Extension.TAG, "YouTube App not installed.");
            return false;
        }
    }

    /** Atomic Integer for generating View ID's */
    private static final AtomicInteger sNextGeneratedId = new AtomicInteger(1);

    /** Generate a View ID with no conflicts */
    public static int generateViewId()
    {
        // Taken from View.generateViewId() which is API 17+
        for (;;)
        {
            final int result = sNextGeneratedId.get();
            // aapt-generated IDs have the high byte nonzero; clamp to the range under that.
            int newValue = result + 1;
            if (newValue > 0x00FFFFFF) newValue = 1; // Roll over to 1, not 0.
            if (sNextGeneratedId.compareAndSet(result, newValue))
            {
                return result;
            }
        }
    }

    // Nested Classes

    /** Video Timer */
    class VideoTimer
    {
        /** Update time */
        private long updateTime;

        /** Runnable */
        private Runnable runnable;

        /** Handler */
        private Handler handler;

        /** Create a new VideoTimer */
        public VideoTimer(final long updateTime)
        {
            // Handler created/run on UI Thread
            this.updateTime = updateTime;
            this.handler = new Handler();
            this.runnable = new Runnable()
            {
                @Override
                public void run()
                {
                    try
                    {
                        sendTimeData();
                    }
                    catch (Throwable t)
                    {
                        Log.e(Extension.TAG, "Could not send time data", t);
                    }
                    handler.postDelayed(runnable, updateTime);
                }
            };
        }

        /** Start */
        public void start()
        {
            // ensure not already started to prevent doubling up callbacks
            stop();
            handler.postDelayed(runnable, updateTime);
        }

        /** Stop */
        public void stop()
        {
            handler.removeCallbacks(runnable);
        }
    }

    /** YouTube Player Fragment */
    public static class CustomPlayerFragment extends YouTubePlayerFragment
    {
        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
        {
            Extension.context.initVideoDialog();
            return super.onCreateView(inflater, container, savedInstanceState);
        }

        @Override public void onSaveInstanceState(Bundle bundle)
        {
            //HACK: This is in place to fix missing parcelable/no class def found errors.
            //see: https://stackoverflow.com/questions/44558166/fatal-exception-java-lang-noclassdeffounderror-rt
            //and: https://stackoverflow.com/questions/44379747/youtube-android-player-api-throws-badparcelableexception-classnotfoundexception
            Extension.context.setVideoSaveEnabled(false);

            super.onSaveInstanceState(bundle);
        }
    }

    // Nested Functions

    /** Base AndroidYoutube Function */
    abstract class AndroidYoutubeFunction implements FREFunction
    {
        // Instance vars

        /** Name */
        protected String name;

        /** Create a new AndroidYoutubeFunction */
        public  AndroidYoutubeFunction(String name)
        {
            this.name=name;
        }

        @Override
        public FREObject call(FREContext freContext, FREObject[] freObjects)
        {
            try
            {
                int res = execute(freObjects);
                return  FREObject.newObject(res);
            }
            catch (Throwable t)
            {
                Log.e(Extension.TAG, "["+this.name+"] Could not retrieve passed FREObject params", t);
                sendError(500, t.getMessage());
                return  null;
            }
        }

        // Implementation

        /** Execute */
        protected abstract int execute(FREObject[] params) throws Exception;
    }

    /** Load URL Function */
    class LoadURLFunction extends AndroidYoutubeFunction
    {
        /** Create a new LoadURLFunction */
        public LoadURLFunction()
        {
            super("LoadURLFunction");
        }

        /** Execute */
        @Override
        protected int execute(FREObject[] params) throws Exception
        {
            String videoId = params[0].getAsString();
            playVideoById(videoId, 0.0f);
            return 0;
        }
    }

    /** Send JSON Function */
    class SendJSONFunction extends AndroidYoutubeFunction
    {
        /** Create a new SendJSONFunction */
        public SendJSONFunction()
        {
            super("SendJSONFunction");
        }

        /** Execute */
        @Override
        protected int execute(FREObject[] params) throws Exception
        {
            String jsonString = params[0].getAsString();
            onMessage(jsonString);
            return 0;
        }
    }

    /** Set Frame Function */
    class SetFrameFunction extends AndroidYoutubeFunction
    {
        /** Create a new SetFrameFunction */
        public SetFrameFunction()
        {
            super("SetFrameFunction");
        }

        /** Execute */
        @Override
        protected int execute(FREObject[] params) throws Exception
        {
            int frameX = params[0].getAsInt();
            int frameY = params[1].getAsInt();
            int frameWidth = params[2].getAsInt();
            int frameHeight = params[3].getAsInt();
            setVideoFrame(frameX, frameY, frameWidth, frameHeight);
            return 0;
        }
    }

    /** Init Video Function */
    class InitVideoFunction extends AndroidYoutubeFunction
    {
        /** Create a new InitVideoFunction */
        public InitVideoFunction()
        {
            super("InitVideoFunction");
        }

        /** Execute */
        @Override
        protected int execute(FREObject[] params) throws Exception
        {
            String devKey = params[0].getAsString();
            initVideo(devKey);
            return 0;
        }
    }

    /** Is Supported Function */
    class IsSupportedFunction implements FREFunction
    {
        @Override
        public FREObject call(FREContext freContext, FREObject[] freObjects)
        {
            try
            {
                boolean isSupported = (hasYouTubeInstalled()) && (getApiLevel() >= 11);
                return FREObject.newObject(isSupported);
            }
            catch (Throwable t)
            {
                Log.e(Extension.TAG, "[IsSupportedFunction] Could not get isSupported", t);
                sendError(500, t.getMessage());
                return  null;
            }
        }
    }

    /** Get Unsupported Reason Function */
    class GetUnsupportedReasonFunction implements FREFunction
    {
        @Override
        public FREObject call(FREContext freContext, FREObject[] freObjects)
        {
            try
            {
                String reason = getUnsupportedReason();
                return FREObject.newObject(reason);
            }
            catch (Throwable t)
            {
                Log.e(Extension.TAG, "[GetUnsupportedReasonFunction] Could not get unsupported reason", t);
                sendError(500, t.getMessage());
                return  null;
            }
        }
    }
}
