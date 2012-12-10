package tcwolf;

import battlecode.common.*;

//A cache built for expensive function calls
//  Two habitually problematic: senseAlliedPowerNodes, senseAlliedArchons
public class DataCache {
	
	private BaseRobot br;
	private RobotController rc;
	
	private MapLocation[] alliedArchons;
	private int alliedArchonsTime = -1;
	
	private MapLocation closestArchon;
	private int closestArchonTime = -1;

	private int moveableDirectionsTime = -1;
	private boolean[] moveableDirections = new boolean[8];
	
	private boolean[] isAdjacentGameObjectGroundCached;
	private boolean[] isAdjacentGameObjectAirCached;
	private GameObject[] adjacentGameObjectsGround =
			new GameObject[Direction.values().length];
	private GameObject[] adjacentGameObjectsAir =
			new GameObject[Direction.values().length];
	private int adjacentGameObjectsTime = -1;
	
	private MapLocation[] capturablePowerCores;
	private int capturablePowerCoresTime = -1;
	
	private PowerNode[] alliedPowerNodes;
	private int alliedPowerNodesTime = -1;
	
	public DataCache(BaseRobot br) {
		this.br = br;
		this.rc = br.rc;
	}
	
	public MapLocation[] getAlliedArchons() {
		if(br.curRound > alliedArchonsTime) {
			alliedArchons = rc.senseAlliedArchons();
			alliedArchonsTime = br.curRound;
		}
		return alliedArchons;
	}
	
	public MapLocation getClosestArchon() {
		if (br.curRound > closestArchonTime) {
			closestArchon = null;
			int closestDistance = Integer.MAX_VALUE;
			for (MapLocation archon : getAlliedArchons()) {
				int distance = br.curLoc.distanceSquaredTo(archon);
				// Except for yourself, that is
				if (!(br.myType == RobotType.ARCHON && distance == 0) &&
						distance < closestDistance) {
					closestArchon = archon;
					closestDistance = distance;
				}
			}
			if(closestArchon==null) 
				closestArchon = br.myHome;
			closestArchonTime = br.curRound;
		}
		return closestArchon;
	}
	
	//Finite directions, not blocked
	public boolean[] getMovableDirections() {
		if (br.curRound > moveableDirectionsTime) {
			for(int i=8; --i>=0;) 
				moveableDirections[i] = rc.canMove(Constants.directions[i]);
			moveableDirectionsTime = br.curRound;
		}
		return moveableDirections;
	}

	public GameObject getAdjacentGameObject(Direction d, RobotLevel level) 
			throws GameActionException {
		if (br.curRound > adjacentGameObjectsTime) {
			isAdjacentGameObjectGroundCached =
					new boolean[Direction.values().length];
			isAdjacentGameObjectAirCached =
					new boolean[Direction.values().length];
			adjacentGameObjectsTime = br.curRound;
		}
		if (level == RobotLevel.ON_GROUND) {
			if (isAdjacentGameObjectGroundCached[d.ordinal()]) {
				return adjacentGameObjectsGround[d.ordinal()];
			} else {
				GameObject obj = rc.senseObjectAtLocation(
						rc.getLocation().add(d), level);
				adjacentGameObjectsGround[d.ordinal()] = obj;
				isAdjacentGameObjectGroundCached[d.ordinal()] = true;
				return obj;
			}
		} else if (level == RobotLevel.IN_AIR){
			if (isAdjacentGameObjectAirCached[d.ordinal()]) {
				return adjacentGameObjectsAir[d.ordinal()];
			} else {
				GameObject obj = rc.senseObjectAtLocation(
						rc.getLocation().add(d), level);
				adjacentGameObjectsAir[d.ordinal()] = obj;
				isAdjacentGameObjectAirCached[d.ordinal()] = true;
				return obj;
			}
		} else {
			return null;
		}
	}
	
	public MapLocation[] getCapturablePowerCores() {
		if (br.curRound > capturablePowerCoresTime) {
			capturablePowerCores = null;
			capturablePowerCoresTime = br.curRound;
		}
		if (capturablePowerCores == null) {
			capturablePowerCores = rc.senseCapturablePowerNodes();
		}
		return capturablePowerCores;
	}
	
	public PowerNode[] getAlliedPowerNodes() {
		if (br.curRound > alliedPowerNodesTime) {
			alliedPowerNodes = null;
			alliedPowerNodesTime = br.curRound;
		}
		if (alliedPowerNodes == null) {
			alliedPowerNodes = rc.senseAlliedPowerNodes();
		}
		return alliedPowerNodes;
	}
	
	public boolean isTowerTargetable(RobotInfo tower) 
			throws GameActionException {
		PowerNode pn = (PowerNode)rc.senseObjectAtLocation(
				tower.location, RobotLevel.POWER_NODE);
		if (pn == null) {
			return false;
		}
		return rc.senseConnected(pn);
	}
	
}
