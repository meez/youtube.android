package com.meez.nativeExtensions
{
	
import flash.events.EventDispatcher;
import flash.events.StatusEvent;
import flash.external.ExtensionContext;
import flash.system.Capabilities;

public class AndroidYouTube extends EventDispatcher 
{
    /** If not supported, this is reason */
    private var unsupportedReason:String;
    
	/** Extension context */
	private var context:ExtensionContext;
	
	/** Create new AndroidWebView */
	public function AndroidYouTube():void 
	{
        try
        {
            this.context = ExtensionContext.createExtensionContext("com.meez.extensions.AndroidYouTube", null);
        }
        catch (e:Error)
        {
            trace(e);
        }
        if (!hasContext())
        {
            trace("[AndroidYouTube] Cannot create AndroidYouTube extension. No video will be supported");
            return;
        }
		this.context.addEventListener(StatusEvent.STATUS, onStatusEvent);
	}

	/** dispose */
	public function dispose():void
	{
        if (!hasContext())
            return;
		this.context.removeEventListener(StatusEvent.STATUS, onStatusEvent);
		this.context.dispose();
        this.context = null;
	}
    
    /**
     * Initialize the video player
     * @param devKey YouTube Developer Key @see https://developers.google.com/youtube/android/player/register
     */
    public function initVideo(devKey:String):void
    {
        this.context.call("initVideo", devKey);
    }
    
    /** is supported */
	public function isSupported():Boolean
	{
		if (!isAndroid())
        {
            this.unsupportedReason = "Not on Android Device";
            return false;
        }
        
        if (!hasContext())
        {
            this.unsupportedReason = "ANE Context could not be created";
            return false;
        }
            
        return this.context.call("isSupported");
	}
	
	//
	// Native functions
	//
	
    /** Load URL */
    public function loadURL(url:String):void
	{
        if (!hasContext())
            return;
		this.context.call("loadURL", url);
	}
	
	/** call javascript */
    public function sendJSON(json:String):Object
	{
        if (!hasContext())
            return null;
		return this.context.call("sendJSON", json);
	}

	/** Set Frame */
	public function setFrame(x:int,y:int,width:int,height:int):void
	{
        if (!hasContext())
            return;
		this.context.call("setFrame",x,y,width,height);
 	} 
	
    /** Get reason why YouTube video is not supported */
    public function getUnsupportedReason():String
    {
        if (this.unsupportedReason==null)
        {
            this.unsupportedReason = String(this.context.call("getUnsupportedReason"));
        }
        return this.unsupportedReason;
    }
    
    //
    // Implementation
    //
	
    /** is android device */
	private function isAndroid():Boolean
	{
		return Capabilities.manufacturer.indexOf('Android') > -1;
	}
    
    /** Context is ready */
    private function hasContext():Boolean
    {
        return this.context != null;
    }
	
	//
	// Events
	//
	
	/** On status event sent from Native Extension context */
	private function onStatusEvent(e:StatusEvent):void
	{
        trace("[AndroidYouTube] onStatusEvent(" + e + ")");
		var type:String = e.code;
		var reason:String = e.level;
		dispatchEvent(new AndroidYouTubeEvent(type, reason));
	}
	
}
	
}