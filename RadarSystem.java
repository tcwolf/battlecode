package tcwolf;

import battlecode.common.Clock;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.GameConstants;
import battlecode.common.MapLocation;
import battlecode.common.PowerNode;
import battlecode.common.Robot;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.RobotLevel;
import battlecode.common.RobotType;
import battlecode.common.Team;

//This class caches data from expensive calls
// * senseNearbyGameObjects (100 cost)
// * senseRobotInfo (25 per robot)
//TODO: Timeouts

public class RadarSystem {

	BaseRobot br;

	public final static int MAX_ROBOTS = 4096;
	public final static int MAX_ENEMY_ROBOTS = 50;
	public final static int MAX_ADJACENT = 17;

	public final RobotInfo[] allyInfos = new RobotInfo[MAX_ROBOTS];
	public final int[] allyTimes = new int[MAX_ROBOTS];
	public final int[] allyRobots = new int[MAX_ROBOTS];

	public int numAdjacentAllies;
	public int numAllyRobots;
	public int numAllyFighters;
	public int numAllyToRegenerate;
	public final RobotInfo[] adjacentAllies = new RobotInfo[MAX_ADJACENT];
	public int alliesOnLeft;
	public int alliesOnRight;
	public int alliesInFront;
	public int numAllyScouts;

	public final RobotInfo[] enemyInfos = new RobotInfo[MAX_ROBOTS];
	public final int[] enemyTimes = new int[MAX_ROBOTS];
	public final int[] enemyRobots = new int[MAX_ENEMY_ROBOTS];
	public int numEnemyRobots;
	public int numEnemyArchons;
	public int numEnemySoldiers;
	public int numEnemyScouts;
	public int numEnemyDisruptors;
	public int numEnemyScorchers;
	public int numEnemyTowers;

	public int vecEnemyX;
	public int vecEnemyY;

	public int centerEnemyX;
	public int centerEnemyY;

	public int centerAllyX;
	public int centerAllyY;

	public int roundsSinceEnemySighted;

	public int lastscanround;
	public boolean needToScanEnemies;
	public boolean needToScanAllies;

	public Robot[] robots;

	// yarchon retreat code
	public int[] closestInDir;
	public int[] numEnemyInDir;
	final static int[] blank_closestInDir = new int[] { 99, 99, 99, 99, 99, 99,
			99, 99, 99, 99 };
	public int[] allies_in_dir;

	public RobotInfo closestEnemy;
	public int closestEnemyDist;
	public RobotInfo closestEnemyWithFlux;
	public int closestEnemyWithFluxDist;

	public RobotInfo closestLowFluxAlly;
	public int closestLowFluxAllyDist;

	public RobotInfo closestAllyScout;
	public int closestAllyScoutDist;

	public RobotInfo lowestFluxAllied;
	public double lowestFlux;
	public RobotInfo lowestEnergonAllied;
	public double lowestEnergonRatio;

	final boolean cachepositions;
	final boolean isArchon;

	private final double[] allyTowerHealth = new double[MAX_ROBOTS];
	private final int[] allyTowerTime = new int[MAX_ROBOTS];

	public RadarSystem(BaseRobot br) {
		this.br = br;
		lastscanround = -1;
		needToScanEnemies = true;
		needToScanAllies = true;
		robots = null;
		closestInDir = new int[10];
		numEnemyInDir = new int[10];
		switch (br.myType) {
		case SOLDIER:
		case SCORCHER:
		case DISRUPTER:
		case SCOUT:
			cachepositions = true;
			isArchon = false;
			break;
		case ARCHON:
			cachepositions = false;
			isArchon = true;
			break;
		default:
			cachepositions = false;
			isArchon = false;
		}
	}

	private void resetEnemyStats() {
		closestEnemy = null;
		closestEnemyDist = Integer.MAX_VALUE;
		closestEnemyWithFlux = null;
		closestEnemyWithFluxDist = Integer.MAX_VALUE;
		numEnemyRobots = 0;
		numEnemyArchons = 0;
		numEnemySoldiers = 0;
		numEnemyScouts = 0;
		numEnemyDisruptors = 0;
		numEnemyScorchers = 0;
		numEnemyTowers = 0;

		vecEnemyX = 0;
		vecEnemyY = 0;
		centerEnemyX = 0;
		centerEnemyY = 0;

		System.arraycopy(blank_closestInDir, 0, closestInDir, 0, 10);
		numEnemyInDir = new int[10];
	}

	private void resetAllyStats() {
		closestAllyScout = null;
		closestAllyScoutDist = Integer.MAX_VALUE;
		numAdjacentAllies = 0;
		numAllyRobots = 0;
		numAllyFighters = 0;
		numAllyToRegenerate = 0;
		numAllyScouts = 0;
		alliesOnLeft = 0;
		alliesOnRight = 0;
		alliesInFront = 0;
		closestLowFluxAlly = null;
		closestLowFluxAllyDist = Integer.MAX_VALUE;
		allies_in_dir = new int[8];
		lowestFluxAllied = null;
		lowestFlux = 9000;
		lowestEnergonAllied = null;
		lowestEnergonRatio = 1.0;

		centerAllyX = 0;
		centerAllyY = 0;
	}

	private void addEnemy(RobotInfo rinfo) throws GameActionException {

		int pos = rinfo.robot.getID();

		MapLocation enemyLoc = rinfo.location;
		int dist = enemyLoc.distanceSquaredTo(br.curLoc);

		switch (rinfo.type) {
		case ARCHON:
			numEnemyArchons++;
			break;
		case DISRUPTER:
			numEnemyDisruptors++;
			break;
		case SCORCHER:
			numEnemyScorchers++;
			break;
		case SCOUT:
			if (dist > 5)
				return;
			else
				numEnemyScouts++;
			break;
		case SOLDIER:
			numEnemySoldiers++;
			break;
		case TOWER:
			if (!br.dc.isTowerTargetable(rinfo))
				return;
			else
				numEnemyTowers++;
			break;
		}

		enemyInfos[pos] = rinfo;
		enemyTimes[pos] = Clock.getRoundNum();
		enemyRobots[numEnemyRobots++] = pos;

		centerEnemyX += enemyLoc.x;
		centerEnemyY += enemyLoc.y;
		if (dist < closestEnemyDist) {
			closestEnemy = rinfo;
			closestEnemyDist = dist;
		}
	}

	private void addEnemyForScout(RobotInfo rinfo) throws GameActionException {

		int pos = rinfo.robot.getID();

		MapLocation eloc = rinfo.location;
		int dist = eloc.distanceSquaredTo(br.curLoc);

		switch (rinfo.type) {
		case ARCHON:
			numEnemyArchons++;
			break;
		case DISRUPTER:
			numEnemyDisruptors++;
			if (rinfo.flux >= 0.15 && dist <= closestEnemyWithFluxDist) {
				closestEnemyWithFlux = rinfo;
				closestEnemyWithFluxDist = dist;
			}
			if (dist < closestEnemyDist) {
				closestEnemy = rinfo;
				closestEnemyDist = dist;
			}
			break;
		case SCORCHER:
			numEnemyScorchers++;
			break;
		case SCOUT:
			if (dist > 5)
				return;
			else
				numEnemyScouts++;
			break;
		case SOLDIER:
			numEnemySoldiers++;
			if (rinfo.flux >= 0.15 && dist < closestEnemyWithFluxDist) {
				closestEnemyWithFlux = rinfo;
				closestEnemyWithFluxDist = dist;
			}
			if (dist < closestEnemyDist) {
				closestEnemy = rinfo;
				closestEnemyDist = dist;
			}
			break;
		case TOWER:
			if (!br.dc.isTowerTargetable(rinfo))
				return;
			else
				numEnemyTowers++;
			break;
		}

		enemyInfos[pos] = rinfo;
		// enemyTimes[pos] = Clock.getRoundNum();
		enemyRobots[numEnemyRobots++] = pos;

		centerEnemyX += eloc.x;
		centerEnemyY += eloc.y;

		Direction dir = br.curLoc.directionTo(eloc);
		switch (rinfo.type) {
		case ARCHON:
		case SOLDIER:
		case SCORCHER:
		case DISRUPTER:
			if (closestInDir[dir.ordinal()] > dist)
				closestInDir[dir.ordinal()] = dist;
			break;
		}
	}

	private void addEnemyForArchon(RobotInfo rinfo) throws GameActionException {

		int rid = rinfo.robot.getID();

		MapLocation eloc = rinfo.location;
		int dist = eloc.distanceSquaredTo(br.curLoc);

		switch (rinfo.type) {
		case ARCHON:
			numEnemyArchons++;
			break;
		case DISRUPTER:
			numEnemyDisruptors++;
			break;
		case SCORCHER:
			numEnemyScorchers++;
			break;
		case SCOUT:
			if (dist > 5)
				return;
			else
				numEnemyScouts++;
			break;
		case SOLDIER:
			numEnemySoldiers++;
			break;
		case TOWER:
			if (!br.dc.isTowerTargetable(rinfo))
				return;
			else
				numEnemyTowers++;
			break;
		}

		enemyInfos[rid] = rinfo;
		enemyTimes[rid] = Clock.getRoundNum();

		enemyRobots[numEnemyRobots++] = rid;

		// Remember the enemy type
		br.tmem.countEnemy(rid, rinfo.type);

		Direction dir = br.curLoc.directionTo(eloc);

		centerEnemyX += eloc.x;
		centerEnemyY += eloc.y;

		if (dist < closestEnemyDist) {
			closestEnemy = rinfo;
			closestEnemyDist = dist;
		}

		switch (rinfo.type) {
		case ARCHON:
		case SOLDIER:
		case SCORCHER:
		case DISRUPTER: {
			if (closestInDir[dir.ordinal()] > dist)
				closestInDir[dir.ordinal()] = dist;
			numEnemyInDir[dir.ordinal()]++;

		}
			break;
		}
	}

	private void addAlly(RobotInfo rinfo) throws GameActionException {
		if (rinfo.type == RobotType.TOWER) {
			checkAlliedTower(rinfo);
			return;
		}

		int pos = rinfo.robot.getID();
		allyRobots[numAllyRobots++] = pos;
		allyInfos[pos] = rinfo;
		allyTimes[pos] = Clock.getRoundNum();

		if (rinfo.location.distanceSquaredTo(br.curLoc) <= 2) {
			adjacentAllies[numAdjacentAllies++] = rinfo;
		}

		int ddir = (br.curLoc.directionTo(rinfo.location).ordinal()
				- br.curDir.ordinal() + 8) % 8;
		if (ddir >= 5)
			alliesOnLeft++;
		else if (ddir >= 1 && ddir <= 3)
			alliesOnRight++;
		if (ddir <= 1 || ddir == 7)
			alliesInFront++;
	}

	/**
	 * Similar to addAlly with some key differences: <br>
	 * - compute number of allys to regenerate in an area <br>
	 * - compute lowest flux ally <br>
	 * 
	 * 
	 * @param rinfo
	 * @throws GameActionException
	 */
	private void addAllyForScout(RobotInfo rinfo) throws GameActionException {
		if (rinfo.type == RobotType.TOWER) {
			checkAlliedTower(rinfo);
			return;
		}

		int pos = rinfo.robot.getID();
		allyRobots[numAllyRobots++] = pos;
		allyInfos[pos] = rinfo;
		allyTimes[pos] = Clock.getRoundNum();

		int dist = br.curLoc.distanceSquaredTo(rinfo.location);

		if ((rinfo.energon < rinfo.type.maxEnergon - 0.2) && !rinfo.regen
				&& dist <= RobotType.SCOUT.attackRadiusMaxSquared) {
			numAllyToRegenerate++;
		}

		if (dist <= 2) {
			adjacentAllies[numAdjacentAllies++] = rinfo;
		}

		double ratio = 2.0;
		double flux = 9999;

		switch (rinfo.type) {
		case ARCHON:
			ratio = rinfo.energon / 150.0;
			break;
		case DISRUPTER:
			ratio = rinfo.energon / 70.0;
			flux = rinfo.flux;
			break;
		case SCORCHER:
			ratio = rinfo.energon / 70.0;
			flux = rinfo.flux;
			break;
		case SCOUT:
			flux = rinfo.flux;

			if (dist < closestAllyScoutDist && rinfo.flux > 0.5) {
				closestAllyScoutDist = dist;
				closestAllyScout = rinfo;
			}
			break;
		case SOLDIER:
			ratio = rinfo.energon / 40.0;
			flux = rinfo.flux;
			break;
		case TOWER:
			break;
		}

		if (!rinfo.regen && ratio < lowestEnergonRatio) {
			lowestEnergonRatio = ratio;
			lowestEnergonAllied = rinfo;
		}

		if (flux < lowestFlux) {
			lowestFlux = rinfo.flux;
			lowestFluxAllied = rinfo;
		}
	}

	private void addAllyForArchon(RobotInfo rinfo) throws GameActionException {
		if (rinfo.type == RobotType.TOWER) {
			checkAlliedTower(rinfo);
			return;
		}

		int pos = rinfo.robot.getID();
		allyInfos[pos] = rinfo;
		allyTimes[pos] = Clock.getRoundNum();

		MapLocation aloc = rinfo.location;

		numAllyRobots++;

		// archon doesn't care about this
		// if (rinfo.energon != rinfo.type.maxEnergon) {
		// numAllyToRegenerate++;
		// }

		if (aloc.distanceSquaredTo(br.curLoc) <= 2) {
			adjacentAllies[numAdjacentAllies++] = rinfo;
		}

		switch (rinfo.type) {
		// case ARCHON:
		case SOLDIER:
		case SCORCHER:
		case DISRUPTER: {
			numAllyFighters++;
			allies_in_dir[br.curLoc.directionTo(aloc).ordinal()]++;
			centerAllyX += aloc.x;
			centerAllyY += aloc.y;
		}
			break;
		case SCOUT: {
			numAllyScouts++;
		}
			break;
		}
	}

	// Call to populate radar information. Ally and Enemy information should
	// only
	// be assumed to be correct if scanAllies and/or scanEnemies is set to true
	public void scan(boolean scanAllies, boolean scanEnemies) {

		if (lastscanround < br.curRound) {
			needToScanAllies = true;
			needToScanEnemies = true;
			lastscanround = br.curRound;
			robots = br.rc.senseNearbyGameObjects(Robot.class);
		}

		if (scanAllies) {
			if (needToScanAllies)
				needToScanAllies = false;
			else
				scanAllies = false;
		}

		if (scanEnemies) {
			if (needToScanEnemies)
				needToScanEnemies = false;
			else
				scanEnemies = false;
		}

		if (scanAllies || scanEnemies) {
			Robot[] robots = this.robots;

			// reset stat collection
			if (scanEnemies)
				resetEnemyStats();
			if (scanAllies)
				resetAllyStats();

			// Bring some vars into local space for the loop
			RobotController rc = br.rc;
			Team myTeam = rc.getTeam();
			switch (br.myType) {
			case ARCHON:
				for (int idx = robots.length; --idx >= 0;) {
					Robot r = robots[idx];
					try {
						if (myTeam == r.getTeam()) {
							if (scanAllies) {
								addAllyForArchon(rc.senseRobotInfo(r));
							}
						} else {
							if (scanEnemies) {
								addEnemyForArchon(rc.senseRobotInfo(r));
							}
						}
					} catch (GameActionException e) {
						// No worries
					}
				}
				break;
			case SCOUT:
				for (int idx = robots.length; --idx >= 0;) {
					Robot r = robots[idx];
					try {
						if (myTeam == r.getTeam()) {
							if (scanAllies) {
								addAllyForScout(rc.senseRobotInfo(r));
							}
						} else {
							if (scanEnemies) {
								addEnemyForScout(rc.senseRobotInfo(r));
							}
						}
					} catch (GameActionException e) {
						// No worries
					}
				}
				break;
			default:
				for (int idx = robots.length; --idx >= 0;) {
					Robot r = robots[idx];
					try {

						if (myTeam == r.getTeam()) {
							if (scanAllies) {
								addAlly(rc.senseRobotInfo(r));
							}
						} else {
							if (scanEnemies) {
								addEnemy(rc.senseRobotInfo(r));
							}
						}
					} catch (GameActionException e) {
						// No worries
					}
				}
				break;

			}

			if (scanEnemies) {
				if (numEnemyRobots == 0) {
					centerEnemyX = centerEnemyY = -1;
					vecEnemyX = br.curLoc.x;
					vecEnemyY = br.curLoc.y;
				} else {
					centerEnemyX = centerEnemyX / numEnemyRobots;
					centerEnemyY = centerEnemyY / numEnemyRobots;
					vecEnemyX = centerEnemyX - br.curLoc.x;
					vecEnemyY = centerEnemyY - br.curLoc.y;
				}

				// compute some global stats
				if (numEnemyRobots == 0) {
					roundsSinceEnemySighted++;
				} else {
					roundsSinceEnemySighted = 0;
				}
			}

			if (scanAllies) {
				if (numAllyFighters == 0) {
					centerAllyX = centerAllyY = -1;
				} else {
					centerAllyX = centerAllyX / numAllyFighters;
					centerAllyY = centerAllyY / numAllyFighters;
				}
			}
		}
	}

	// Check if we have scanned enemies this round
	public boolean hasScannedEnemies() {
		return (lastscanround == br.curRound && !needToScanEnemies);
	}

	// Check if we have scanned allies this round
	public boolean hasScannedAllies() {
		return (lastscanround == br.curRound && !needToScanAllies);
	}

	// Get the difference in strength between the two swarms

	public int getArmyDifference() {
		return numAllyRobots - numEnemyRobots;
	}

	// Gets the calculated swarm target in order to chase an enemy swarm
	public MapLocation getEnemySwarmTarget() {
		double a = Math.sqrt(vecEnemyX * vecEnemyX + vecEnemyY * vecEnemyY) + .001;

		return new MapLocation((int) (vecEnemyX * 7 / a) + br.curLoc.x,
				(int) (vecEnemyY * 7 / a) + br.curLoc.y);
	}

	// Gets the calculated enemy swarm center
	public MapLocation getEnemySwarmCenter() {
		return new MapLocation(centerEnemyX, centerEnemyY);
	}

	// Gets the calculated ally swarm center
	public MapLocation getAllySwarmCenter() {
		return new MapLocation(centerAllyX, centerAllyY);
	}

	public int getAlliesInDirection(Direction dir) {
		if (dir == null || dir == Direction.NONE || dir == Direction.OMNI)
			return 0;
		return allies_in_dir[dir.ordinal()]
				+ allies_in_dir[(dir.ordinal() + 1) % 8]
				+ allies_in_dir[(dir.ordinal() + 7) % 8];
	}

	// Gets the enemy info from the radar into your own and nearby robots'
	// extended radar.
	public void broadcastEnemyInfo(boolean sendOwnInfo) {
		int localNumEnemyRobots = numEnemyRobots;
		if (localNumEnemyRobots == 0 && !sendOwnInfo)
			return;
		int[] shorts = new int[(localNumEnemyRobots) * 5
				+ (sendOwnInfo ? 5 : 0)];
		if (sendOwnInfo) {
			shorts[0] = br.myID;
			shorts[1] = br.curLoc.x;
			shorts[2] = br.curLoc.y;
			shorts[3] = 10000 + (int) Math.ceil(Util
					.getOwnStrengthEstimate(br.rc));
			shorts[4] = br.myType.ordinal();
		}
		for (int i = 0, c = sendOwnInfo ? 5 : 0; i < localNumEnemyRobots; i++, c += 5) {
			RobotInfo ri = enemyInfos[enemyRobots[i]];
			shorts[c] = ri.robot.getID();
			shorts[c + 1] = ri.location.x;
			shorts[c + 2] = ri.location.y;
			shorts[c + 3] = (int) Math.ceil(Util.getEnemyStrengthEstimate(ri));
			shorts[c + 4] = br.myType == RobotType.SCOUT ? 55555 : br.curLoc
					.distanceSquaredTo(ri.location);
		}
		if (br.myType == RobotType.SOLDIER || br.myType == RobotType.DISRUPTER
				|| br.myType == RobotType.SCORCHER)
			br.er.integrateEnemyInfo(shorts);
		br.io.sendUShorts(BroadcastChannel.EXTENDED_RADAR,
				BroadcastType.ENEMY_INFO, shorts);
	}

	// Checks allied towers for loss of health and sets
	// Doomsday flag, if neccesary
	private void checkAlliedTower(RobotInfo rinfo) throws GameActionException {

		int round = Clock.getRoundNum();

		if (round >= 1995 && !br.gameEndNow) {
			int id = rinfo.robot.getID();
			double curEnergon = rinfo.energon;
			double oldEnergon = allyTowerHealth[id];

			// if within two rounds
			if (allyTowerTime[id] >= round - 3) {

				// minimum damage possible / round is 1.7(R) - 0.2(C)
				if ((oldEnergon - curEnergon) > 0.0
						&& (oldEnergon - curEnergon < 1.0)) {

					// if we're connected
					PowerNode pn = (PowerNode) br.rc.senseObjectAtLocation(
							rinfo.location, RobotLevel.POWER_NODE);
					if (br.rc.senseConnected(pn)) {
						br.gameEndDetected = true;

						int estEndTime;
						if (br.rc.senseOpponentConnected(pn)) {
							// compute when I think the game will end
							estEndTime = (int) (250 / (GameConstants.TIME_LIMIT_DAMAGE / br.dc
									.getAlliedPowerNodes().length))
									+ Clock.getRoundNum()
									- Constants.ENDGAME_CAP_MODE_BUFFER;
						} else {
							estEndTime = (int) (curEnergon / (GameConstants.TIME_LIMIT_DAMAGE / br.dc
									.getAlliedPowerNodes().length))
									+ Clock.getRoundNum()
									- Constants.ENDGAME_CAP_MODE_BUFFER;
						}

						if (estEndTime < br.gameEndTime) {
							br.gameEndTime = estEndTime;
							// br.dbg.println('e', "GAME ENDS AT " +
							// (br.gameEndTime +
							// Constants.ENDGAME_CAP_MODE_BUFFER) +
							// " | OH SHIT MODE AT " + br.gameEndTime);
						}

					}
				}
			}

			allyTowerHealth[id] = curEnergon;
			allyTowerTime[id] = round;
		}
	}

}
