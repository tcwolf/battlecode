package tcwolf;

import battlecode.common.MapLocation;
import battlecode.common.Message;

public class MatchObservationSystem {
	
	// A tracer needs to be placed, and all strings that contain
	//  the delimiter need to be thrown out
	//TODO: Encrypt?
	private static final String DELIMITER = "#~#";
	private BaseRobot br;
	
	public MatchObservationSystem(BaseRobot myBR) {
		br = myBR;
	}
	
	public void rememberString(String s) {
//		br.rc.addMatchObservation(s);
	}
	
	// Serializes a message into a string and remembers it.
	public void rememberMessage(Message m) {
		String s = serializeMessageToString(m);
		rememberString(s);
	}
	
	private static String serializeMessageToString(Message m) {
		if (m == null) {
			return "";
		}
		String s = DELIMITER;
		if (m.ints != null) {
			s = s.concat(m.ints.length + DELIMITER);
			for (int myInt : m.ints) {
				s = s.concat(myInt + DELIMITER);
			}
		} else {
			s = s.concat(0 + DELIMITER);
		}
		if (m.strings != null) {
			s = s.concat(m.strings.length + DELIMITER);
			for (String myString : m.strings) {
				s = s.concat(myString + DELIMITER);
			}
		} else {
			s = s.concat(0 + DELIMITER);
		}
		if (m.locations != null) {
			s = s.concat(m.locations.length + DELIMITER);
			for (MapLocation myLoc : m.locations) {
				if (myLoc==null)
					s = s.concat("null").concat(DELIMITER);
				else
					s = s.concat(myLoc.x + "," + myLoc.y + DELIMITER);
			}
		} else {
			s = s.concat(0 + DELIMITER);
		}
		return s;
	}
	
	private static Message deserializeMessageFromString(String s) {
		int[] myInts;
		String[] myStrings;
		MapLocation[] myLocs;
		try {
			s = s.substring(s.indexOf(DELIMITER) + DELIMITER.length());
			int numInts = Integer.parseInt(s.substring(0, s.indexOf(DELIMITER)));
			myInts = new int[numInts];
			for (int i = 0; i < numInts; i++) {
				s = s.substring(s.indexOf(DELIMITER) + DELIMITER.length());
				myInts[i] = Integer.parseInt(s.substring(0, s.indexOf(DELIMITER)));
			}
			s = s.substring(s.indexOf(DELIMITER) + DELIMITER.length());
			int numStrings = Integer.parseInt(s.substring(0, s.indexOf(DELIMITER)));
			myStrings = new String[numStrings];
			for (int i = 0; i < numStrings; i++) {
				s = s.substring(s.indexOf(DELIMITER) + DELIMITER.length());
				myStrings[i] = s.substring(0, s.indexOf(DELIMITER));
			}
			s = s.substring(s.indexOf(DELIMITER) + DELIMITER.length());
			int numLocs = Integer.parseInt(s.substring(0, s.indexOf(DELIMITER)));
			myLocs = new MapLocation[numLocs];
			for (int i = 0; i < numLocs; i++) {
				s = s.substring(s.indexOf(DELIMITER) + DELIMITER.length());
				int myLocX = Integer.parseInt(s.substring(0, s.indexOf(",")));
				int myLocY = Integer.parseInt(s.substring(s.indexOf(",") + 1,
						s.indexOf(DELIMITER)));
				myLocs[i] = new MapLocation(myLocX, myLocY);
			}
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
		Message m = new Message();
		m.ints = myInts;
		m.strings = myStrings;
		m.locations = myLocs;
		return m;
	}
	
	public static void test() {
		Message m = new Message();
		m.ints = new int[] {10,11,12};
		m.strings = new String[] {"sup", "hi", "enemy over here!", "yoyoyoyo"};
		m.locations = new MapLocation[] {new MapLocation(22, 55)};
		String s = serializeMessageToString(m);
		System.out.println(s);
		System.out.println(s);
		System.out.println(s);
		Message m2 = deserializeMessageFromString(s);
		for (int a : m2.ints) System.out.println(a);
		for (String b : m2.strings) System.out.println(b);
		for (MapLocation c : m2.locations) System.out.println(c);
	}
	
	public static void main(String[] args) {
		String matchObservationString = "";
		boolean wasEncrypted = false;
		int roundNum = -1;

		Message m = deserializeMessageFromString(matchObservationString);
		for (int a : m.ints) System.out.println(a);
		for (String b : m.strings) System.out.println(b);
		for (MapLocation c : m.locations) System.out.println(c);
	}
}
