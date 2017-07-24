package meez.nativeExtensions.androidyoutube;

import android.util.Log;
import com.adobe.fre.FREContext;
import com.adobe.fre.FREExtension;

public class Extension implements FREExtension
{
    /** Logging tag */
	public static final String TAG = "[AndroidYouTube]";

	/** Native extension context */
	public static AndroidYoutubeContext context;

	/** Create the context (AS to Java). */
	public FREContext createContext(String extId)
	{
		Log.d(TAG, "AndroidYouTubeExtension.createContext()");
		return context = new AndroidYoutubeContext();
	}

	/** Dispose */
	public void dispose()
	{
		Log.d(TAG, "AndroidYouTubeExtension.dispose");

        // Context is disposed within AndroidYouTubeContext
	}

	/** Initialize the context. */
	public void initialize()
    {
        Log.d(TAG, "AndroidYouTubeExtension.initialize");
        // nothing happening here.
    }

}
