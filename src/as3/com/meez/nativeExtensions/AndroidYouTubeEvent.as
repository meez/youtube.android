package com.meez.nativeExtensions 
{
	
import flash.events.Event;

/** AndroidWebViewEvent */
public class AndroidYouTubeEvent extends Event 
{
	//
	// Definitions
	//
	
	/** Event Types */
	public static const LOAD_COMPLETE:String="loadComplete";
	public static const LOAD_FAILED:String="loadFailed";
	public static const MESSAGE:String="message";
	public static const VIDEO_ERROR:String="videoError";
 	
	public var value:String;
	
	//
	// Public Methods
	//
	
	/** Create new AndroidWebViewEvent */
	public function AndroidYouTubeEvent(type:String, value:String, bubbles:Boolean=false, cancelable:Boolean=false) 
	{ 
		this.value = value;
		super(type, bubbles, cancelable);
	} 
	
	/** clone */
	public override function clone():Event 
	{ 
		return new AndroidYouTubeEvent(type, value, bubbles, cancelable);
	} 
	
	/** To String */
	public override function toString():String 
	{ 
		return formatToString("AndroidYouTubeEvent", "type", "value", "bubbles", "cancelable", "eventPhase"); 
	}
	
}
	
}