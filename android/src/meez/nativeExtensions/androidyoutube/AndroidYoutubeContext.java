package meez.nativeExtensions.androidyoutube;

import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import android.annotation.TargetApi;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import com.adobe.fre.FREContext;
import com.adobe.fre.FREFunction;
import com.adobe.fre.FREObject;
import com.google.android.youtube.player.YouTubePlayer;
import org.json.JSONObject;

@TargetApi(Build.VERSION_CODES.HONEYCOMB)
public class AndroidYoutubeContext extends FREContext implements YouTubePlayerAdapter.PlayerEventCallback
{
    // Definitions

    /** Reason Video player is unsupported */
    private String unsupportedReason;

    /** Adapter */
    private YouTubePlayerAdapter adapter;

    /** Create a new AndroidYoutubeContext */
    public AndroidYoutubeContext()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext()");

        this.unsupportedReason="";
    }

    /** Dispose */
    @Override
    public void dispose()
    {
        Log.d(Extension.TAG, "AndroidYouTubeContext.dispose()");

        YouTubePlayerAdapter.releaseInstance();
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

    // Create PlayerActions

    /** Play video by ID (YouTube video id) */
    public YouTubePlayerAdapter.PlayerAction createPlayByIdAction(final String videoId, final double startTime)
    {
        return new YouTubePlayerAdapter.PlayerAction() {

            @Override
            public void run(YouTubePlayer player) throws Exception
            {
                Log.d(Extension.TAG, "AndroidYouTubeContext.playVideoById("+videoId+")");

                player.loadVideo(videoId,(int)(1000 * startTime));
            }
        };
    }

    /** Play Video */
    public YouTubePlayerAdapter.PlayerAction createPlayVideoAction()
    {
        return new YouTubePlayerAdapter.PlayerAction()
        {
            @Override
            public void run(YouTubePlayer player) throws Exception
            {
                player.play();
            }
        };
    }

    /** Pause Video */
    public YouTubePlayerAdapter.PlayerAction createPauseAction()
    {
        return new YouTubePlayerAdapter.PlayerAction() {

            @Override
            public void run(YouTubePlayer player) throws Exception
            {
                player.pause();
            }
        };
    }

    /** Seek in Video */
    public YouTubePlayerAdapter.PlayerAction createSeekAction(final double time) throws Exception
    {
        return new YouTubePlayerAdapter.PlayerAction()
        {
            @Override
            public void run(YouTubePlayer player) throws Exception
            {
                int dur = player.getDurationMillis();
                int timeInMilliseconds = (int)(1000 * time);

                // prevent seeking past video end
                if (dur>0 && timeInMilliseconds>=dur)
                {
                    // go back 1 second from video end
                    timeInMilliseconds = Math.max(0, dur-1000);
                }
                player.seekToMillis(timeInMilliseconds);
            }
        };
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
            this.adapter.execute(createPlayVideoAction());

        }else if(action.equals("playById"))
        {
            String videoId=msgObj.getString("videoId");
            double startTime = msgObj.getDouble("startTime");

            this.adapter.execute(createPlayByIdAction(videoId,startTime));
        }

        else if(action.equals("stop"))
        {
            this.adapter.execute(createPauseAction());
        }

        else if(action.equals("pause"))
        {
            this.adapter.execute(createPauseAction());
        }

        else if(action.equals("seek"))
        {
            this.adapter.execute(createSeekAction(msgObj.getDouble("time")));
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

    // YouTubePlayerAdapter.PlayerEventCallback impl

    /** Send JSON encoded Message (to Actionscript) */
    public void sendMessage(JSONObject msg)
    {
        String msgString=msg.toString();
        dispatchEventWithReason("message", msgString);
    }

    /** Send generic data to Actionscript */
    public void sendData(String data)
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
    public void sendTimeData(int curSecs, int totalSecs)
    {
        try
        {
            JSONObject timeObj=new JSONObject();
            timeObj.put("current", curSecs);
            timeObj.put("total", totalSecs);
            JSONObject msgObj=new JSONObject();
            msgObj.put("type", "time");
            msgObj.put("time", timeObj);

            sendMessage(msgObj);
        }
        catch(Throwable t)
        {
            Log.w(Extension.TAG, "Could not send time data", t);
        }
    }

    /** Send player state to Actionscript */
    public void sendState(String state)
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
    public void sendError(int code, String msg)
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

        return apiLevel;
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
            adapter.execute(createPlayByIdAction(videoId,0.0f));
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
            adapter.setVideoFrame(frameX, frameY, frameWidth, frameHeight);
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

            adapter=YouTubePlayerAdapter.fetchInstance();

            adapter.init(getActivity(),devKey,AndroidYoutubeContext.this);

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
