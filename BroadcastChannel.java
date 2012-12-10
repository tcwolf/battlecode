package tcwolf;

//Available Broadcast Channels
public enum BroadcastChannel {

	ALL,
	FIGHTERS,
	SCOUTS,
	EXTENDED_RADAR,
	EXPLORERS,
	;

	//The 2 char channel header
	public final String chanHeader;
	
	private BroadcastChannel() {
		chanHeader = BroadcastSystem.CHANHEADER_S + ((char)this.ordinal());
	}
}
