package tcwolf;

import battlecode.common.MapLocation;
import battlecode.common.Message;

//Code sample taken from Cory Li's team 
//  Useless against computer players, but added as it's interesting...

 // Messaging attack stuff. We attempt to detect what team we are playing
 // against based on their message format and message dumps from the scrim
 // server/seeding tournament. We then periodically broadcast some "fake"
 // enemy message.
public class MessageAttackSystem {
	
	private final BaseRobot br;
	
	/** Memorized message. */
	private Message memorizedMessage;
	/** The time the memorized message was sent. */
	private int memorizedMessageTime;
	
	/** The enemy team number, -1 if we don't know. */
	private int enemyTeam;
	
	/** Maps round number to ints used in generating an enemy message. */
	private int[][] messageData;
	
	/** Whether the loadMessageData method has been called. */
	private boolean loaded;
	
	public MessageAttackSystem(BaseRobot myBR) {
		br = myBR;
		memorizedMessage = null;
		memorizedMessageTime = -1;
		enemyTeam = -1;
		messageData = new int[16384][0];
		loaded = false;
	}
	
	/**
	 * Assert the enemy team number. This should be called if we guessed the
	 * enemy team in a previous round.
	 * @param enemyTeam the enemy team number
	 */
	public void assertEnemyTeam(int enemyTeam) {
		this.enemyTeam = enemyTeam;
	}
	
	/**
	 * Guess which team we are playing given one of their messages. Guess is
	 * based on messages sent in past games.
	 * @param m An enemy message.
	 * @return True if we set a guess on the enemy team, False otherwise.
	 */
	public boolean detectEnemyTeam(Message m) {
		
		// return if we already made a guess
		if (enemyTeam != -1) {
			return false;
		}
		
		// return if message is null
		if (m == null) {
			return false;
		}
		
		// 016: Team 16: seems to always send 3 ints and a map location... 3rd
		// i think 1st int is message type, 2nd int is round num, 3rd is a hash,
		// and map loc is a target
		if (m.ints != null && m.ints.length == 3 && isRoundNum(m.ints[1]) &&
				(m.strings == null || m.strings.length == 0) &&
				m.locations != null && m.locations.length == 1) {
			enemyTeam = 16;
		
		// 029: Tera-bull: no strings, just 5 ints and maplocs for Archons...
		// non-Archons seem to send only 3 ints, but we'll ignore those
		} else if (m.ints != null && m.ints.length == 5 && isRoundNum(m.ints[1]) &&
				(m.strings == null || m.strings.length == 0)) {
			enemyTeam = 29;
		
		// 031: Yippee: another string user... 1 int 1 string, int is a hash...
		// don't appear to explicitly use the current round, so we're trying a
		// rebroadcast attack
		// 086: Boxdrop: they use the same 1 int 1 string format
		} else if (m.ints != null && m.ints.length == 1 &&
				m.strings != null && m.strings.length == 1 &&
				(m.locations == null || m.locations.length == 0)) {
			enemyTeam = 86;
		
		// 047: fun gamers: for testing purposes only...
		} else if (m.ints != null && (m.ints.length == 2 || m.ints.length == 3) &&
				m.strings != null && m.strings.length == 1 &&
				(m.locations == null || m.locations.length == 0)) {
			enemyTeam = 47;
		
		// 053: Chaos Legion: sends 1 encrypted string
		} else if ((m.ints == null || m.ints.length == 0) &&
				m.strings != null && m.strings.length == 1 &&
				(m.locations == null || m.locations.length == 0)) {
			enemyTeam = 53;
		
		// 056: PhysicsBot: 4 ints, 5 strings, lots of maplocs... so much flux
		// ... also, i think the first int is the hash and that it is (something) +
		// 17 * roundNum
		} else if (m.ints != null && m.ints.length == 4 &&
				m.strings != null && m.strings.length == 5) {
			enemyTeam = 56;
		
		// 096: Apathy: although apathy is closed to any sort of naive message
		// attacks, his messages are very distinctive in that the round number
		// is encoded in the map location (the y coordinate)
		//
		// thanks to boxdrop for their message dumps from the seeding tourney <3
		} else if ((m.ints == null || m.ints.length == 0) &&
				m.strings != null && m.strings.length == 1 &&
				m.locations != null && m.locations.length == 1 &&
				isRoundNum(m.locations[0].y)) {
			enemyTeam = 96;
		}
		
		// memorize the message that tipped us off and the time we got it
		if (enemyTeam != -1) {
			memorizedMessage = m;
			memorizedMessageTime = br.curRound;
			return true;
			
		// no match :(
		} else {
			return false;
		}
	}
	
	/**
	 * Returns which team we think we are playing against.
	 * @return enemyTeam The team number we think we are playing against,
	 * -1 if we have no guess.
	 */
	public int guessEnemyTeam() {
		return enemyTeam;
	}
	
	/**
	 * Initialize the message attack system based on the enemy team guess.
	 */
	public void load() {
		
		// if we haven't guessed the enemy team, return
		if (enemyTeam == -1) {
			return;
		}
		
		// if we already loaded message data, return
		if (loaded) {
			return;
		}
		
		// load message data if appropriate
		switch (enemyTeam) {
			case 16:
				load016();
				break;
			case 29:
			case 86:
			case 47:
			case 53:
			case 56:
			default:
				break;
		}
		
		loaded = true;
	}
	
	/**
	 * Returns whether the message attack system has been initialized and is
	 * ready to generate enemy messages.
	 * @return True if the system is loaded, False otherwise.
	 */
	public boolean isLoaded() {
		return loaded;
	}
	
	/**
	 * Returns an enemy message to send to the enemy team.
	 * @return An enemy message. May return null.
	 */
	public Message getEnemyMessage() {
		
		// if we haven't loaded message data, return null
		if (!loaded) {
			return null;
		}
		
		// return enemy message
		Message m = null;
		switch (enemyTeam) {
			case 16:
				int[] data = messageData[br.curRound];
				// if we don't have any message data for this round, return old message
				if (data != null && data.length != 4) {
					m = new Message();
					m.ints = new int[] {data[0], br.curRound, data[1]};
					m.locations = new MapLocation[] {new MapLocation(data[2], data[3])};
				} else {
					m = memorizedMessage;
					m.ints[1] = br.curRound;
				}
				break;
			case 29:
				m = memorizedMessage;
				m.ints[1] = br.curRound;
				break;
			case 86:
				m = memorizedMessage;
				if (m.strings[0].length() > 3) {
					m.strings[0] = "" + (char)(m.strings[0].charAt(0) - 1) + 
							(char)(m.strings[0].charAt(1) + 31) + m.strings[0].substring(2);
				}
				break;
			case 47:
				m = new Message();
				m.ints = new int[] {5555, 0, br.curRound};
				m.strings = new String[] {"Robert'); DROP TABLE Students;--"};
				break;
			case 53:
				m = memorizedMessage;
				m.strings[0] = "!sup!" + m.strings[0];
				break;
			case 56:
				m = memorizedMessage;
				m.ints[0] = m.ints[0] + 17 * (br.curRound - memorizedMessageTime);
				break;
			case 96:
				m = memorizedMessage;
				break;
			default:
				break;
		}
		return m;
	}
	
	/**
	 * Loads previous messages from team016 into messageData.
	 */
	private void load016() {
		// AUTO GENERATED CODE
		messageData[614] = new int[] {133136254, 315446129, 16383, 31314};
		messageData[636] = new int[] {133136254, 315446086, 16381, 31315};
		messageData[670] = new int[] {133136254, 315436408, 16384, 31316};
		messageData[789] = new int[] {133136254, 315436655, 16385, 31316};
		messageData[811] = new int[] {133136254, 315445740, 16383, 31317};
		messageData[811] = new int[] {133136254, 315445740, 16383, 31317};
		messageData[827] = new int[] {133136254, 315445706, 16383, 31315};
		messageData[865] = new int[] {133136254, 315436682, 16384, 31320};
		messageData[1117] = new int[] {133136254, 315433203, 16385, 31320};
		messageData[1306] = new int[] {133136254, 315433586, 16389, 31315};
		messageData[1359] = new int[] {133136254, 315433695, 16389, 31316};
		messageData[1425] = new int[] {133136254, 315433828, 16389, 31315};
		messageData[1657] = new int[] {133136254, 315434172, 16389, 31323};
		messageData[1677] = new int[] {133136254, 315434323, 16385, 31320};
		messageData[2945] = new int[] {133136254, 315440998, 16401, 31333};
		messageData[3058] = new int[] {133136254, 315441030, 16400, 31330};
		messageData[3083] = new int[] {133136254, 315437129, 16400, 31327};
		messageData[3112] = new int[] {133136254, 315437102, 16398, 31328};
		messageData[3365] = new int[] {133136254, 315437621, 16398, 31329};
		messageData[3385] = new int[] {133136254, 315437569, 16393, 31338};
		messageData[3403] = new int[] {133136254, 315437801, 16398, 31329};
		messageData[3405] = new int[] {133136254, 315437792, 16396, 31334};
		messageData[3407] = new int[] {133136254, 315437793, 16398, 31329};
		messageData[3414] = new int[] {133136254, 315437791, 16393, 31338};
		messageData[3449] = new int[] {133136254, 315437706, 16401, 31353};
		messageData[3452] = new int[] {133136254, 315437709, 16402, 31351};
		messageData[3464] = new int[] {133136254, 315437936, 16405, 31333};
		messageData[3967] = new int[] {133136254, 315438734, 16401, 31345};
		messageData[4597] = new int[] {133136254, 315427724, 16410, 31340};
		messageData[5094] = new int[] {133136254, 315428783, 16413, 31342};
		messageData[5182] = new int[] {133136254, 315424799, 16413, 31342};
		messageData[5218] = new int[] {133136254, 315424941, 16399, 31350};
		messageData[5218] = new int[] {133136254, 315424953, 16413, 31344};
		messageData[5255] = new int[] {133136254, 315425132, 16412, 31342};
		messageData[5480] = new int[] {133136254, 315425458, 16412, 31342};
		messageData[5626] = new int[] {133136254, 315425682, 16412, 31338};
		messageData[5751] = new int[] {133136254, 315425928, 16412, 31338};
		messageData[5886] = new int[] {133136254, 315426203, 16413, 31338};
		messageData[6028] = new int[] {133136254, 315426686, 16410, 31340};
		messageData[6212] = new int[] {133136254, 315431150, 16411, 31341};
		messageData[6259] = new int[] {133136254, 315431046, 16412, 31340};
		messageData[6505] = new int[] {133136254, 315431605, 16415, 31336};
		messageData[6653] = new int[] {133136254, 315431836, 16413, 31339};
		messageData[6827] = new int[] {133136254, 315432241, 16413, 31338};
		messageData[7101] = new int[] {133136254, 315432733, 16413, 31338};
		messageData[7319] = new int[] {133136254, 315429199, 16414, 31343};
		messageData[7440] = new int[] {133136254, 315429500, 16419, 31343};
		messageData[7517] = new int[] {133136254, 315429595, 16414, 31343};
		messageData[7575] = new int[] {133136254, 315429708, 16415, 31341};
		messageData[7776] = new int[] {133136254, 315430054, 16412, 31338};
		messageData[7954] = new int[] {133136254, 315430520, 16418, 31342};
		messageData[8026] = new int[] {133136254, 315430612, 16415, 31343};
		messageData[8245] = new int[] {133136254, 315451444, 16416, 31342};
		messageData[8337] = new int[] {133136254, 315451712, 16413, 31343};
		messageData[8632] = new int[] {133136254, 315452176, 16412, 31340};
		messageData[8973] = new int[] {133136254, 315453051, 16412, 31341};
		messageData[8846] = new int[] {133136254, 315452738, 16417, 31343};
		messageData[9068] = new int[] {133136254, 315453114, 16413, 31343};
		messageData[9148] = new int[] {133136254, 315453210, 16413, 31343};
		messageData[9295] = new int[] {133136254, 315449536, 16419, 31341};
		messageData[9466] = new int[] {133136254, 315449768, 16417, 31341};
		messageData[9791] = new int[] {133136254, 315450407, 16420, 31341};
		messageData[9947] = new int[] {133136254, 315450863, 16420, 31341};
		messageData[10387] = new int[] {133136254, 315455870, 16420, 31340};
		messageData[10575] = new int[] {125828966, 277838490, 16402, 31334};
		messageData[10768] = new int[] {133136254, 315456638, 16420, 31338};
		// END AUTO GENERATED CODE
	}
	
	private boolean isRoundNum(int field) {
		return field == br.curRound || field == br.curRound - 1;
	}
	
	public static void main(String[] args) {
		MapLocation home = new MapLocation(16374,31306);
		System.out.println(home.hashCode());
		MapLocation[] locs = new MapLocation[] {new MapLocation(16420,31338)};
		System.out.println(locs.hashCode());
	}
}
