package meez.nativeExtensions.androidyoutube;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.util.Log;
import android.view.*;
import android.widget.FrameLayout;
import com.google.android.youtube.player.YouTubeInitializationResult;
import com.google.android.youtube.player.YouTubePlayer;
import com.google.android.youtube.player.YouTubePlayerFragment;

import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;

/** YouTubePlayerAdapter */
public class YouTubePlayerAdapter implements YouTubePlayer.OnInitializedListener, YouTubePlayer.PlayerStateChangeListener,YouTubePlayer.PlaybackEventListener
{
    /** ANE State */
    private enum State
    {
        INITIALIZING,
        PLAYER_INIT,
        READY,
        DISPOSING,
        DISPOSED;
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

    /** Instance */
    private static YouTubePlayerAdapter _instance;

    /** Dialog Box to hold video player view */
    private Dialog dialog;

    /** Layout Parameters */
    private WindowManager.LayoutParams layoutParams;

    /** Current State */
    private State state;

    /** YouTube Player */
    private YouTubePlayer player;

    /** Video Container */
    private FrameLayout videoContainer;

    /** Activity */
    private Activity activity;

    /** Developer Key */
    private String devKey;

    /** Video Time Timer */
    private VideoTimer videoTimer;

    /** Action Queue */
    private Queue<PlayerAction> actionQueue;

    /** Player Fragment */
    private CustomPlayerFragment fragment;

    /** Event Callback */
    private YouTubePlayerAdapter.PlayerEventCallback callback;

    // Public Methods

    /** YouTubePlayerAdapter */
    public YouTubePlayerAdapter()
    {
        changeState(State.INITIALIZING);
        this.actionQueue=new LinkedList<PlayerAction>();
    }

    /** Get Instance */
    public static YouTubePlayerAdapter fetchInstance()
    {
        if(_instance==null)
        {
            _instance=new YouTubePlayerAdapter();
        }
        return _instance;
    }

    /** Release */
    public static void releaseInstance()
    {
        if(_instance==null)
            return;

        _instance.dispose();
        _instance=null;
    }

    /** Player Action */
    public interface PlayerAction
    {
        void run(YouTubePlayer player) throws Exception;
    }

    /** Player Event Callback */
    interface PlayerEventCallback
    {
        void sendState(String state);
        void sendData(String data);
        void sendTimeData(int curSecs, int totalSecs);
        void sendError(int code, String msg);
        void dispatchEventWithReason(String type, String reason);
    }

    /**
     * Initialize the video player
     * @param activity Target activity that the YouTubePlayerFragment will belong to
     * @param devKey Developer key from google/youtube
     * @param eventCallback Callback object for sending back player events and state changes
     */
    public void init(Activity activity, String devKey, PlayerEventCallback eventCallback)
    {
        Log.d(Extension.TAG, "YouTubePlayerAdapter.init()");

        // Should only be called 1x during init state
        assertState(State.INITIALIZING);

        this.activity=activity;
        this.devKey=devKey;
        this.callback=eventCallback;

        // Create views
        this.videoContainer = createVideoContainer();
        this.videoContainer.setId(YouTubePlayerAdapter.generateViewId());
        this.layoutParams = createLayoutParams();
        this.dialog = createDialog();

        //Hack - videoContainer is only added to root view long enough to attach fragment (fragment must be attached to view in Android 'display list').
        // In Fragment's onViewCreated() videoContainer is removed from root container and set as view of Dialog
        this.videoContainer.setVisibility(View.INVISIBLE);
        getRootContainer().addView(this.videoContainer);

        YouTubePlayerFragment playerFragment = createPlayerFragment();

        // Add YouTube fragment to video container
        this.activity
                .getFragmentManager()
                .beginTransaction()
                .add(this.videoContainer.getId(), playerFragment)
                .commit();
    }

    /** Dispose */
    public void dispose()
    {
        changeState(State.DISPOSING);

        stopUpdateTimer();

        getRootContainer().removeView(videoContainer);
        this.videoContainer.removeAllViews();
        this.dialog.dismiss();

        try
        {
            this.fragment.setRetainInstance(false);

            // Removing fragment will call onDestroyView() on the fragment, which will release the YouTubePlayer
            // see https://developers.google.com/youtube/android/player/reference/com/google/android/youtube/player/YouTubePlayerFragment#Overview
            this.activity.getFragmentManager()
                    .beginTransaction()
                    .remove(this.fragment)
                    .commit();
        }
        catch (Throwable t)
        {
            Log.w(Extension.TAG, "Error removing player fragment", t);
        }
        finally
        {
            changeState(State.DISPOSED);
        }
    }

    // Actions

    /** Execute */
    public void execute(PlayerAction action)
    {
        this.actionQueue.add(action);

        if(!checkState(State.READY))
            return;

        executeOutstandingActions();
    }

    // Views

    /** Get Root Container ViewGroup */
    public ViewGroup getRootContainer()
    {
        return (ViewGroup)((ViewGroup)this.activity.findViewById(android.R.id.content)).getChildAt(0);
    }

    /** Create Video Container Layout */
    public FrameLayout createVideoContainer()
    {
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        FrameLayout layout = new FrameLayout(this.activity);
        layout.setLayoutParams(params);

        return layout;
    }

    /** Create YouTube Player Fragment */
    public YouTubePlayerFragment createPlayerFragment()
    {
        YouTubePlayerAdapter.CustomPlayerFragment fragment = new YouTubePlayerAdapter.CustomPlayerFragment();
        fragment.setRetainInstance(true);
        return fragment;
    }

    /** Create Video layout params */
    public WindowManager.LayoutParams createLayoutParams()
    {
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.gravity = Gravity.TOP | Gravity.LEFT;
        params.dimAmount = 0.0f;
        params.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE |
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS |
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL |
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
        return params;
    }

    /** Create Video Dialog  */
    public Dialog createDialog()
    {
        Dialog dialog = new Dialog(this.activity);
        dialog.setCancelable(false);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        return dialog;
    }

    // YouTubePlayer.OnInitializedListener Implementation

    @Override
    public void onInitializationFailure(YouTubePlayer.Provider provider, YouTubeInitializationResult errorReason)
    {
        Log.w(Extension.TAG, "YouTubePlayerAdapter.onInitializaionFailure(). Could not Initialize player. Reason ("+errorReason.toString()+")");

        this.callback.dispatchEventWithReason("videoError", "Could not initialize video player");
    }

    @Override
    public void onInitializationSuccess(YouTubePlayer.Provider provider, YouTubePlayer player, boolean wasRestored)
    {
        Log.d(Extension.TAG, "YouTubePlayerAdapter.onInitSuccess (player="+player+", wasRestored="+wasRestored+")");

        if (!checkState(State.PLAYER_INIT))
            return;

        changeState(State.READY);

        this.player=player;
        this.player.setPlayerStateChangeListener(this);
        this.player.setPlaybackEventListener(this);

        if (!wasRestored)
        {
            // no video player controls
            this.player.setPlayerStyle(YouTubePlayer.PlayerStyle.CHROMELESS);
        }

        executeOutstandingActions();

        this.callback.sendData("playerReady");
    }

    //  YouTubePlayer.PlayerStateChangeListener Implementation

    @Override
    public void onAdStarted()
    {
        if(!checkState(State.READY))
            return;

        Log.d(Extension.TAG, "Ad Started");
        this.callback.sendData("Ad started");
    }

    @Override
    public void onError(YouTubePlayer.ErrorReason errorReason)
    {
        if(!checkState(State.READY))
            return;

        Log.w(Extension.TAG, "YouTubePlayerAdapter.onError() error: ("+errorReason.toString()+")");

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
        // Unknown reason
        else
        {
            msg=errorReason.toString();
            code=500; // Meez API Error Code
        }

        this.callback.sendError(code, msg);
    }

    @Override
    public void onLoaded(String arg0)
    {
        if(!checkState(State.READY))
            return;

        Log.d(Extension.TAG, "YouTubePlayerAdapter.onLoaded("+arg0+")");

        this.callback.sendState(PLAYER_STATE_UNSTARTED);
    }

    @Override
    public void onLoading()
    {
        if(!checkState(State.READY))
            return;

        Log.d(Extension.TAG, "YouTubePlayerAdapter.onLoading");

        this.callback.sendState(PLAYER_STATE_BUFFERING);
    }

    @Override
    public void onVideoEnded()
    {
        if(!checkState(State.READY))
            return;

        Log.d(Extension.TAG, "YouTubePlayerAdapter.onVideoEnded");

        this.callback.sendState(PLAYER_STATE_ENDED);
    }

    @Override
    public void onVideoStarted()
    {
        if(!checkState(State.READY))
            return;

        Log.d(Extension.TAG, "YouTubePlayerAdapter.onVideoStarted");
        // Nothing to send
    }

    // YouTubePlayer.PlaybackEventListener Implementation

    @Override
    public void onBuffering(boolean isBuffering)
    {
        if(!checkState(State.READY))
            return;

        if (isBuffering)
        {
            this.callback.sendState(PLAYER_STATE_BUFFERING);
        }
    }

    @Override
    public void onPaused()
    {
        if(!checkState(State.READY))
            return;

        Log.d(Extension.TAG, "YouTubePlayerAdapter.onPaused");

        stopUpdateTimer();
        this.callback.sendState(PLAYER_STATE_PAUSED);
    }

    @Override
    public void onPlaying()
    {
        if(!checkState(State.READY))
            return;

        Log.d(Extension.TAG, "YouTubePlayerAdapter.onPlaying");

        startUpdateTimer();
        this.callback.sendState(PLAYER_STATE_PLAYING);
    }

    @Override
    public void onSeekTo(int newPositionMillis)
    {
        if(!checkState(State.READY))
            return;

        Log.d(Extension.TAG, "YouTubePlayerAdapter.onSeekTo("+Integer.toString(newPositionMillis)+")");
    }

    @Override
    public void onStopped()
    {
        if(!checkState(State.READY))
            return;

        Log.d(Extension.TAG, "YouTubePlayerAdapter.onStopped");

        stopUpdateTimer();
    }

    // Fragment Callbacks

    /** On Fragment View Created */
    public void onFragmentViewCreated(CustomPlayerFragment fragment)
    {
        if (!checkState(State.INITIALIZING))
            return;

        this.fragment=fragment;

        // Remove from root and add to dialog
        getRootContainer().removeView(this.videoContainer);

        // hide video offscreen until explicitly positioned by Actionscript call
        setVideoFrame(-5000, -5000, 600, 400);
        this.videoContainer.setVisibility(View.VISIBLE);

        // add to dialog and show
        this.dialog.setContentView(videoContainer);
        this.dialog.show();

        // Initialize the video player fragment
        changeState(State.PLAYER_INIT);
        fragment.initialize(this.devKey, this);
    }

    /** On Fragment View Destroyed */
    public void onFragmentViewDestroyed()
    {
        // youtube player destroyed, set state to allow it to be initialized with dev key if created again
        changeState(State.INITIALIZING);
    }

    /** On Fragment View Started */
    public void onFragmentViewStarted(CustomPlayerFragment fragment)
    {
        // HACK: if fragment is recreated (e.g. after memory cleanup) it will not be added as child of videoContainer
        if(fragment.getView().getParent()==null)
        {
            this.videoContainer.addView(fragment.getView(),new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,FrameLayout.LayoutParams.MATCH_PARENT));
        }
    }

    /** On Fragment View Started */
    public void onFragmentViewStopped(CustomPlayerFragment fragment)
    {
    }

    /** On Fragment Save Instance */
    public void onFragmentSaveInstance(boolean saveState)
    {
        // Called from Youtube Fragment
        setVideoSaveEnabled(saveState);
    }

    // Frame

    /** Set video frame */
    public void setVideoFrame(int x, int y, int width, int height)
    {
        Log.d(Extension.TAG, String.format("YouTubePlayerAdapter.setVideoFrame(%d, %d, %d, %d)", x, y, width, height));

        this.layoutParams.width = width;
        this.layoutParams.height = height;
        this.layoutParams.x = x;
        this.layoutParams.y = y;

        this.dialog.getWindow().setAttributes(this.layoutParams);
    }

    // State

    /** Change state */
    private State changeState(State newState)
    {
        State oldState = this.state;
        this.state = newState;
        return oldState;
    }

    /** Check the current state */
    private boolean checkState(State expState)
    {
        boolean correct=this.state.equals(expState);
        if (!correct)
        {
            Log.w(Extension.TAG, "Check state failed. Expected ("+expState+"). Actual ("+this.state+")");
        }
        return correct;
    }

    /** Assert a State */
    private void assertState(State s)
    {
        if (!checkState(s))
        {
            throw new IllegalStateException("Expected state("+s+"). Actual state ("+this.state+")");
        }
    }

    // Implementation

    /** Execute Outstanding Actions */
    private void executeOutstandingActions()
    {
        assertState(State.READY);

        while(!this.actionQueue.isEmpty())
        {
            try
            {
                this.actionQueue.remove().run(this.player);
            }
            catch(Exception e)
            {
                Log.w(Extension.TAG,"Error during player action execution", e);
            }
        }
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

    /** Callable on View Interface */
    public interface CallableOnView
    {
        void call(View view);
    }

    /** Set Save Enabled on Video view and its children */
    protected void setVideoSaveEnabled(final boolean value)
    {
        recursiveCallOnView(this.videoContainer, new CallableOnView(){
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

    /** YouTube Player Fragment */
    public static class CustomPlayerFragment extends YouTubePlayerFragment
    {
        /** On Create View */
        @Override public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
        {
            Log.d(Extension.TAG, "CustomPlayerFragment.onCreateView()");

            YouTubePlayerAdapter.fetchInstance().onFragmentViewCreated(this);

            return super.onCreateView(inflater, container, savedInstanceState);
        }

        @Override
        public void onStart()
        {
            Log.d(Extension.TAG, "CustomPlayerFragment.onStart()");

            YouTubePlayerAdapter.fetchInstance().onFragmentViewStarted(this);

            super.onStart();
        }

        @Override
        public void onStop()
        {
            Log.d(Extension.TAG, "CustomPlayerFragment.onStop()");

            YouTubePlayerAdapter.fetchInstance().onFragmentViewStopped(this);

            super.onStop();
        }

        /** Dispose */
        @Override
        public void onDestroyView()
        {
            Log.d(Extension.TAG, "CustomPlayerFragment.onDestroy()");

            YouTubePlayerAdapter.fetchInstance().onFragmentViewDestroyed();

            super.onDestroyView();
        }

        /** On Save Instance State */
        @Override public void onSaveInstanceState(Bundle bundle)
        {
            Log.d(Extension.TAG, "CustomPlayerFragment.onSaveInstanceState()");

            //HACK: This is in place to fix missing parcelable/no class def found errors.
            //see: https://stackoverflow.com/questions/44558166/fatal-exception-java-lang-noclassdeffounderror-rt
            //and: https://stackoverflow.com/questions/44379747/youtube-android-player-api-throws-badparcelableexception-classnotfoundexception
            YouTubePlayerAdapter.fetchInstance().onFragmentSaveInstance(false);

            super.onSaveInstanceState(bundle);
        }
    }


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

    /** Send Time Data */
    private void sendTimeData()
    {
        // if not playing, send no data
        if (!this.player.isPlaying())
            return;

        //sendMessage({type:"time", time:{current:c, total:t}});
        int curSecs = player.getCurrentTimeMillis()/1000;
        int totalSecs = player.getDurationMillis()/1000;

        this.callback.sendTimeData(curSecs,totalSecs);
    }

    /** Atomic Integer for generating View ID's */
    private static final AtomicInteger sNextGeneratedId = new AtomicInteger(1);

    /** Generate a View ID with no conflicts */
    public static int generateViewId()
    {
        //HACK this is taken from View.generateViewId() but that is API 17+
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
}
