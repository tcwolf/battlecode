package tcwolf;

//Types of messages
public enum BroadcastType {
	
	NONE,
	POWERNODE_FRAGMENTS,
	MAP_FRAGMENTS,
	MAP_EDGES,
	GUESS_ENEMY_TEAM,
	ENEMY_INFO,
	ENEMY_KILL,
	SWARM_TARGET,
	ENEMY_SPOTTED,
	LOW_FLUX_HELP,
	DETECTED_GAME_END,
	;

	//Message header
	public final char header_c;
	
	//Message header, string
	public final String header_s;
	
	//Decode to enum
	public static BroadcastType decode(char header) {
		return BroadcastType.values()[header];
	}
	
	private BroadcastType() {
		header_c = (char)(this.ordinal());
		header_s = String.valueOf(header_c);
	}
	
}
