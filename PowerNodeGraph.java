package tcwolf;

import battlecode.common.MapLocation;

//Sub data structure for map caching that stores information about power nodes
public class PowerNodeGraph {
	final MapLocation[] nodeLocations;
	short nodeCount;
	short nodeSensedCount;
	final boolean[] nodeSensed;
	final short[][] adjacencyList;
	final short[] degreeCount;
	short enemyPowerCoreID;

	public PowerNodeGraph() {
		nodeLocations = new MapLocation[51];
		adjacencyList = new short[51][50];
		degreeCount = new short[51];
		nodeSensed = new boolean[51];
	}

	@Override
	public String toString() {
		String ret = "\n";
		for (int i = 1; i <= nodeCount; i++) {
			ret += "node #" + i + " " + nodeLocations[i] + " " + nodeSensed[i];
			for (int j = 0; j < degreeCount[i]; j++) {
				ret += " " + adjacencyList[i][j];
			}
			ret += "\n";
		}
		ret += "enemy core is node #" + enemyPowerCoreID + "\n";
		return ret;
	}

}
