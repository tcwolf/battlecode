package tcwolf;

import battlecode.common.Clock;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.PowerNode;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.RobotLevel;
import battlecode.common.RobotType;
import battlecode.common.TerrainTile;

public class ArchonRobot extends BaseRobot {
	private enum StrategyState {
		INITIAL_EXPLORE, RETURN_HOME, DEFEND, RUSH, ADJACENT_CAP, EFFICIENT_CAP, ENDGAME_CAP,
	}

	private enum BehaviorState {
		SWARM, RETREAT, BATTLE,
	}

	/** round we are releasing our lock */
	int stayTargetLockedUntilRound;
	int roundStartWakeupMode;
	MapLocation target;
	boolean movingTarget;
	Direction targetDir;
	StrategyState strategy;
	BehaviorState behavior;
	MapLocation previousWakeupTarget;
	MapLocation enemySpottedTarget;
	int enemySpottedRound;
	MapLocation[] neighborsOfPowerCore;
	MapLocation nextRandomCapTarget;
	MapLocation adjNode;
	RobotType nextUnitToMake;

	Direction lastFlee;
	double spawnScoutProb;
	double spawnDisrupterProb;
	double soldierDisrupterRatio;
	double scoutRatio;

	int enemyTeam = -1;

	static final int RETREAT_RADIUS = 6;
	static final int RETREAT_RADIUS_CLOSE = 3;
	static final int RETREAT_DISTANCE = 8;
	static final int CHASE_COMPUTE_RADIUS = 7;
	static final int TURNS_TO_LOCK_ONTO_AN_ENEMY = 30;
	static final int TURNS_TO_RETREAT = 30;
	MapLocation lastPowerNodeGuess;

	public ArchonRobot(RobotController myRC) throws GameActionException {
		super(myRC);

		// Bind radio channels
		io.setChannels(new BroadcastChannel[] { BroadcastChannel.ALL,
				BroadcastChannel.EXPLORERS, BroadcastChannel.EXTENDED_RADAR });

		// Read/write team memory
		if (myArchonID == 0)
			tmem.advanceRound();
		tmem.initReadEnemyCount();

		// Set subsystem modes
		fbs.setPoolMode();
		nav.setNavigationMode(NavigationMode.TANGENT_BUG);

		// Initialize starting behaviors
		strategy = StrategyState.INITIAL_EXPLORE;
		behavior = BehaviorState.SWARM;

		// Initialize state variables
		stayTargetLockedUntilRound = -Integer.MAX_VALUE;
		enemySpottedRound = -55555;
		enemySpottedTarget = null;
		lastPowerNodeGuess = null;
		lastFlee = null;
		nextRandomCapTarget = null;
		nextUnitToMake = getNextUnitToSpawn();
		neighborsOfPowerCore = rc.sensePowerCore().neighbors();
		initSpawnProbabilities();

		if (tmem.getEnemyTeam() != 0)
			enemyTeam = tmem.getEnemyTeam();
	}

	@Override
	public void run() throws GameActionException {

		adjNode = getNextPowerNodeAdjacentToCore();

		// The new strategy transition
		if (enemyTeam != 16) {
			if (gameEndNow && myArchonID <= 3)
				strategy = StrategyState.ENDGAME_CAP;
			switch (strategy) {
			case INITIAL_EXPLORE:
				if (curRound > 100)
					strategy = StrategyState.DEFEND;
				break;
			case RETURN_HOME:
				if (curLoc.distanceSquaredTo(myHome) <= 8)
					strategy = StrategyState.DEFEND;
				break;
			case DEFEND:
				if (curRound > 1600) {
					if (adjNode == null)
						strategy = StrategyState.EFFICIENT_CAP;
					else if (myArchonID != 0)
						strategy = StrategyState.ADJACENT_CAP;
				}
				break;
			case ADJACENT_CAP:
				if (adjNode == null)
					strategy = StrategyState.EFFICIENT_CAP;
				break;
			case EFFICIENT_CAP:
				if (adjNode != null)
					strategy = StrategyState.RETURN_HOME;
				break;
			case ENDGAME_CAP:
				break;
			default:
				break;
			}
		} else {
			// Strategy transition for team 16
			if (gameEndNow && myArchonID <= 3)
				strategy = StrategyState.ENDGAME_CAP;
			switch (strategy) {
			case INITIAL_EXPLORE:
				if (curRound > 50)
					strategy = StrategyState.DEFEND;
				break;
			case RETURN_HOME:
				if (curLoc.distanceSquaredTo(myHome) <= 8)
					strategy = StrategyState.DEFEND;
				break;
			case DEFEND:
				if (curRound > 2400) {
					strategy = StrategyState.EFFICIENT_CAP;
				}
				break;
			case EFFICIENT_CAP:
				break;
			case ENDGAME_CAP:
				break;
			default:
				break;
			}
		}
		// If insufficiently prepared, prepare
		if (nav.getTurnsPrepared() < TangentBug.DEFAULT_MIN_PREP_TURNS)
			nav.prepare();

		// Scan
		boolean needToScanAllies = !rc.isMovementActive() || msm.justMoved();
		boolean needToScanEnemies = !rc.isMovementActive() || msm.justMoved()
				|| curRound % 6 == myArchonID;
		radar.scan(needToScanAllies, needToScanEnemies);
		if (needToScanEnemies)
			updateSoldierDisruptorRatio();
		if (needToScanAllies)
			updateScoutProb();

		// Broadcast enemy info every 6 turns
		if (curRound % 6 == myArchonID)
			radar.broadcastEnemyInfo(false);

		// Update retreat behavior
		if (behavior != BehaviorState.RETREAT)
			lastFlee = null;
		if (behavior == BehaviorState.RETREAT && radar.getArmyDifference() > 3)
			stayTargetLockedUntilRound = -55555;

		// If there is a non-scout enemy in sensor range, set target as enemy
		// swarm target
		if (radar.closestEnemy != null
				&& radar.numEnemyScouts < radar.numEnemyRobots) {
			if (radar.closestEnemy.type == RobotType.ARCHON
					|| radar.closestEnemy.type == RobotType.TOWER
					|| radar.closestEnemy.flux > 0.15) {
				enemySpottedRound = curRound;
				enemySpottedTarget = radar.closestEnemy.location;
			}
			stayTargetLockedUntilRound = curRound + TURNS_TO_LOCK_ONTO_AN_ENEMY;
			Direction enemySwarmDir = curLoc.directionTo(radar
					.getEnemySwarmTarget());
			if (radar.getArmyDifference() < -2
					|| radar.getAlliesInDirection(enemySwarmDir) < radar.numEnemyRobots
							- radar.numEnemyArchons - radar.numEnemyTowers) {
				stayTargetLockedUntilRound = curRound + TURNS_TO_RETREAT;
				behavior = BehaviorState.RETREAT;
				String ret = "";
				// ret = computeRetreatTarget();
				// ret = computeRetreatTargetLoose();
				// ret = computeRetreatTarget2();
				// ret = computeRetreatTarget3();
				ret = computeRetreatTarget4();
				projectTargetOntoMap();
				// dbg.setIndicatorString('e', 1,
				// "Target= "+locationToVectorString(target)+
				// ", Strategy="+strategy+", Behavior="+behavior+" "+ret);

			} else {
				if (strategy == StrategyState.RETURN_HOME
						|| strategy == StrategyState.ENDGAME_CAP) {
					resetTarget();
				} else {
					behavior = BehaviorState.BATTLE;
					if (strategy != StrategyState.DEFEND)
						computeBattleTarget();
				}
			}

			// Update the target based on the previous target
			// Decide if we are chasing or retreating
		} else if (curRound <= stayTargetLockedUntilRound && targetDir != null) {
			if (behavior == BehaviorState.RETREAT)
				updateRetreatTarget();

			// If someone else told us of a recent enemy spotting, go to that
			// location
		} else if (curRound < enemySpottedRound
				+ Constants.ENEMY_SPOTTED_SIGNAL_TIMEOUT) {
			if (strategy == StrategyState.RETURN_HOME
					|| strategy == StrategyState.ENDGAME_CAP) {
				resetTarget();
			} else {
				behavior = curLoc.distanceSquaredTo(enemySpottedTarget) <= 256 ? BehaviorState.BATTLE
						: BehaviorState.SWARM;
				if (strategy != StrategyState.DEFEND) {
					target = enemySpottedTarget;
					movingTarget = true;
				}
				if (curLoc.distanceSquaredTo(enemySpottedTarget) <= 16) {
					enemySpottedTarget = null;
					enemySpottedRound = -55555;
				}

			}

			// If we haven't seen anyone for a while, go back to swarm mode and
			// reset target
		} else {
			resetTarget();
		}

		// If we change to a new target, wake up hibernating allies
		if (previousWakeupTarget == null
				|| target.distanceSquaredTo(previousWakeupTarget) > 25
				|| (!movingTarget && !target.equals(previousWakeupTarget))) {
			roundStartWakeupMode = curRound;
			previousWakeupTarget = target;
		}
		if (curRound % 6 == myArchonID && curRound < roundStartWakeupMode + 50) {
			io.sendWakeupCall();
		}

		// Set the target for the navigator
		nav.setDestination(target);

		// Set the flux balance mode
		if (behavior == BehaviorState.SWARM
				&& strategy != StrategyState.ENDGAME_CAP
				&& curRound > enemySpottedRound
						+ Constants.ENEMY_SPOTTED_SIGNAL_TIMEOUT)
			fbs.setBatteryMode();
		else
			fbs.setPoolMode();

		// Broadcast my target info to the soldier swarm every 6 turns
		if (curRound % 6 == myArchonID
				&& strategy != StrategyState.INITIAL_EXPLORE) {
			int[] shorts = new int[3];
			shorts[0] = movingTarget ? 1 : 0;
			shorts[1] = target.x;
			shorts[2] = target.y;
			io.sendUShorts(BroadcastChannel.FIGHTERS,
					BroadcastType.SWARM_TARGET, shorts);
		}

		// Broadcast a possibly out of date enemy sighting every 20 turns
		if (enemySpottedTarget != null && curRound % 20 == myArchonID * 3) {
			int[] shorts = new int[3];
			shorts[0] = enemySpottedRound;
			shorts[1] = enemySpottedTarget.x;
			shorts[2] = enemySpottedTarget.y;
			io.sendUShorts(BroadcastChannel.ALL, BroadcastType.ENEMY_SPOTTED,
					shorts);
		}

		// Rebroadcast enemy team number
		if (enemyTeam != -1 && curRound % 20 == (myArchonID * 3 + 10) % 20) {
			io.sendUShort(BroadcastChannel.EXPLORERS,
					BroadcastType.GUESS_ENEMY_TEAM, enemyTeam);
		}
	}

	@Override
	public MoveInfo computeNextMove() throws GameActionException {
		// This behavior is only executed at the beginning of the round
		//  If it's between round 100 and 150, generate a Scout
		if (curRound >= 100 && curRound <= 150 && rc.getFlux() > 90) {
			if (myArchonID == 0) {
				if (curRound % 10 == 0) {
					Direction dir = curDir;
					while (rc.senseTerrainTile(curLoc.add(dir)) == TerrainTile.OFF_MAP
							|| rc.senseObjectAtLocation(curLoc.add(dir),
									RobotLevel.IN_AIR) != null)
						dir = dir.rotateLeft();
					return new MoveInfo(RobotType.SCOUT, dir);
				} else
					return null;
			} else if (myArchonID == 1) {
				if (curRound % 10 == 5) {
					Direction dir = curDir;
					while (rc.senseTerrainTile(curLoc.add(dir)) == TerrainTile.OFF_MAP
							|| rc.senseObjectAtLocation(curLoc.add(dir),
									RobotLevel.IN_AIR) != null)
						dir = dir.rotateLeft();
					return new MoveInfo(RobotType.SCOUT, dir);
				} else
					return null;
			}
		}

		// Retreat behavior
		if (behavior == BehaviorState.RETREAT) {
			nav.setDestination(target);
			if (rc.getFlux() > 130) {
				for (int d = curDir.ordinal(); d < curDir.ordinal() + 8; d++)
					if (rc.canMove(Constants.directions[d % 8]))
						return new MoveInfo(RobotType.SOLDIER,
								Constants.directions[d % 8]);
			}
			Direction d = nav.navigateToDestination();
			if (d != null)
				return new MoveInfo(d, true);
			else
				new MoveInfo(nav.navigateGreedy(target), true);
		}

		// If we have sufficient flux, make a unit
		int fluxToMakeUnitAt;
		switch (behavior) {
		case SWARM:
			fluxToMakeUnitAt = 280;
			break;
		case BATTLE:
			fluxToMakeUnitAt = ((int) nextUnitToMake.spawnCost) + 15;
			break;
		default:
			fluxToMakeUnitAt = 55555;
			break;
		}
		if (rc.getFlux() > fluxToMakeUnitAt) {
			if (nextUnitToMake == RobotType.SCOUT) {
				Direction dir = curDir;
				while (dir != curDir.rotateLeft()) {
					if (rc.senseTerrainTile(curLoc.add(dir)) != TerrainTile.OFF_MAP
							&& rc.senseObjectAtLocation(curLoc.add(dir),
									RobotLevel.IN_AIR) == null) {
						RobotType t = nextUnitToMake;
						nextUnitToMake = (enemyTeam == 16) ? getNextUnitToSpawnTeam16()
								: getNextUnitToSpawn();
						return new MoveInfo(t, dir);
					}
					dir = dir.rotateRight();
				}
			} else {
				Direction dir = nav.wiggleToMovableDirection(curDir);
				if (dir != null) {
					RobotType t = nextUnitToMake;
					nextUnitToMake = (enemyTeam == 16) ? getNextUnitToSpawnTeam16()
							: getNextUnitToSpawn();
					return new MoveInfo(t, dir);
				}
			}
		}

		// If there's an enemy within 20 distance, and we're in battle or we've been
		// weakened, run away
		//TODO: Suicide option (or DoS option)
		if (radar.closestEnemyDist <= 20
				&& (behavior == BehaviorState.BATTLE || curEnergon < 100)) {
			return new MoveInfo(curLoc.directionTo(radar.getEnemySwarmCenter())
					.opposite(), true);
		}

		switch (strategy) {
		case INITIAL_EXPLORE:
			if (curRound > 80 && Util.randDouble() < 0.3)
				return new MoveInfo(nav.navigateCompletelyRandomly(), false);

			return new MoveInfo(getDirAwayFromAlliedArchons(400), false);

		case RETURN_HOME:
			return new MoveInfo(nav.navigateToDestination(), false);

		case DEFEND:
			if (curLoc.distanceSquaredTo(myHome) <= 100) {
				if (Util.randDouble() < 0.02)
					return new MoveInfo(nav.navigateCompletelyRandomly(), false);
				else
					return new MoveInfo(getDirAwayFromAlliedArchons(32), false);
			} else {
				return new MoveInfo(nav.navigateToDestination(), false);
			}

		case RUSH:
		case ADJACENT_CAP:
		case ENDGAME_CAP:
		case EFFICIENT_CAP:
			// If we can build a tower at our target node, do so
			if (rc.canMove(curDir) && curLocInFront.equals(target)
					&& mc.isPowerNode(curLocInFront)) {
				if (rc.getFlux() > 200)
					return new MoveInfo(RobotType.TOWER, curDir);
				else
					return null;
			}

			// If we are on top of our target node, move backwards randomly
			if (curLoc.equals(target) && mc.isPowerNode(curLoc)) {
				return new MoveInfo(nav.navigateCompletelyRandomly(), true);
			}

			if (strategy != StrategyState.ENDGAME_CAP) {
				// If I'm the closest archon to my target...
				MapLocation closestToTarget = null;
				int minDist = Integer.MAX_VALUE;
				for (MapLocation loc : dc.getAlliedArchons()) {
					int dist = loc.distanceSquaredTo(target);
					if (dist < minDist) {
						closestToTarget = loc;
						minDist = dist;
					}
				}
				if (curLoc.equals(closestToTarget)) {
					// If there are no allies in front, slow down (maintain
					// compact swarm)
					if (behavior == BehaviorState.SWARM
							&& radar.alliesInFront == 0
							&& Util.randDouble() < 0.8)
						return null;

					// If I'm NOT the closest archon to my target...
				} else {
					// If I'm too close to an allied archon, disperse
					// probabilistically
					if (dc.getClosestArchon() != null) {
						int distToNearestArchon = curLoc.distanceSquaredTo(dc
								.getClosestArchon());
						if (distToNearestArchon <= 25
								&& Util.randDouble() < 1.05 - Math
										.sqrt(distToNearestArchon) / 10) {
							return new MoveInfo(
									getDirAwayFromAlliedArchons(32), false);
						}
					}
				}
			}

			// By default, move to the destination
			Direction dir = nav.navigateToDestination();
			if (dir == null)
				return null;
			else if (curLoc.add(dir).equals(nav.getDestination()))
				return new MoveInfo(dir);
			else
				return new MoveInfo(dir, false);
		default:
			return null;
		}
	}

	@Override
	public void processMessage(BroadcastType msgType, StringBuilder sb)
			throws GameActionException {
		MapLocation newLoc;
		int enemyDist;
		switch (msgType) {
		case ENEMY_SPOTTED:
			int[] shorts = BroadcastSystem.decodeUShorts(sb);
			newLoc = new MapLocation(shorts[1], shorts[2]);
			enemyDist = enemySpottedTarget == null ? 55555 : curLoc
					.distanceSquaredTo(enemySpottedTarget);
			if (enemyDist <= 16)
				break;
			if ((curRound > enemySpottedRound + 19 && shorts[0] > enemySpottedRound)
					|| enemyDist > curLoc.distanceSquaredTo(newLoc)) {
				enemySpottedRound = shorts[0];
				enemySpottedTarget = newLoc;
			}
			break;
		case ENEMY_INFO:
			newLoc = BroadcastSystem.decodeSenderLoc(sb);
			enemyDist = enemySpottedTarget == null ? 55555 : curLoc
					.distanceSquaredTo(enemySpottedTarget);
			if (enemyDist <= 16)
				break;
			if (curRound > enemySpottedRound + 20
					|| enemyDist > curLoc.distanceSquaredTo(newLoc)) {
				enemySpottedRound = curRound;
				enemySpottedTarget = newLoc;
			}
			break;
		case MAP_EDGES:
			ses.receiveMapEdges(BroadcastSystem.decodeUShorts(sb));
			break;
		case MAP_FRAGMENTS:
			ses.receiveMapFragment(BroadcastSystem.decodeInts(sb));
			break;
		case POWERNODE_FRAGMENTS:
			ses.receivePowerNodeFragment(BroadcastSystem.decodeInts(sb));
			break;
		case GUESS_ENEMY_TEAM:
			if (enemyTeam == -1) {
				enemyTeam = BroadcastSystem.decodeShort(sb);
			}
		default:
			super.processMessage(msgType, sb);
		}
	}

	@Override
	public void useExtraBytecodes() throws GameActionException {
		if (Clock.getRoundNum() % 6 == myArchonID) {
			if (Clock.getRoundNum() == curRound
					&& Clock.getBytecodesLeft() > 5000)
				ses.broadcastMapFragment();
			if (Clock.getRoundNum() == curRound
					&& Clock.getBytecodesLeft() > 3000)
				ses.broadcastPowerNodeFragment();
			if (Clock.getRoundNum() == curRound
					&& Clock.getBytecodesLeft() > 2000)
				ses.broadcastMapEdges();
		}
		super.useExtraBytecodes();
		if (Clock.getRoundNum() == curRound
				&& Clock.getRoundNum() > tmem.timeCountWritten + 10) {
			tmem.writeEnemyCount();
		}
		while (Clock.getRoundNum() == curRound
				&& Clock.getBytecodesLeft() > 3000)
			nav.prepare();
		while (Clock.getRoundNum() == curRound
				&& Clock.getBytecodesLeft() > 1000)
			mc.extractUpdatedPackedDataStep();
	}

	private void resetTarget() {
		behavior = BehaviorState.SWARM;
		movingTarget = false;
		switch (strategy) {
		case RUSH:
			computeExploreTarget();
			break;
		case ADJACENT_CAP:
			target = adjNode;
			break;
		case EFFICIENT_CAP:
			target = mc.guessBestPowerNodeToCapture();
			break;
		case ENDGAME_CAP:
			if (nextRandomCapTarget == null
					|| !isCapturableNode(nextRandomCapTarget))
				nextRandomCapTarget = mc.getEndGamePowerNodeToCapture();
			target = nextRandomCapTarget;
			break;
		default:
			target = myHome;
			break;
		}
	}

	private String computeRetreatTarget() {
		lastPowerNodeGuess = null;
		// 7 0 1
		// 6 2
		// 5 4 3
		// int[] closest_in_dir = er.getEnemiesInEachDirectionOnly();
		int[] closest_in_dir = radar.closestInDir;
		int[] wall_in_dir = new int[8];

		// now, deal with when we are close to map boundaries
		if (mc.edgeXMax != 0
				&& mc.cacheToWorldX(mc.edgeXMax) < curLoc.x + RETREAT_RADIUS) {
			if (mc.edgeYMax != 0
					&& mc.cacheToWorldY(mc.edgeYMax) < curLoc.y
							+ RETREAT_RADIUS) {
				// we are near the SOUTH_EAST corner
				wall_in_dir[1] = wall_in_dir[2] = wall_in_dir[3] = wall_in_dir[4] = wall_in_dir[5] = 1;
			} else if (mc.edgeYMin != 0
					&& mc.cacheToWorldY(mc.edgeYMin) > curLoc.y
							- RETREAT_RADIUS) {
				// we are near the NORTH_EAST corner
				wall_in_dir[1] = wall_in_dir[2] = wall_in_dir[3] = wall_in_dir[0] = wall_in_dir[7] = 1;
			} else {
				// we are near the EAST edge
				wall_in_dir[1] = wall_in_dir[2] = wall_in_dir[3] = 1;
			}
		} else if (mc.edgeXMin != 0
				&& mc.cacheToWorldX(mc.edgeXMin) > curLoc.x - RETREAT_RADIUS) {
			if (mc.edgeYMax != 0
					&& mc.cacheToWorldY(mc.edgeYMax) < curLoc.y
							+ RETREAT_RADIUS) {
				// we are near the SOUTH_WEST corner
				wall_in_dir[7] = wall_in_dir[6] = wall_in_dir[5] = wall_in_dir[4] = wall_in_dir[3] = 1;
			} else if (mc.edgeYMin != 0
					&& mc.cacheToWorldY(mc.edgeYMin) > curLoc.y
							- RETREAT_RADIUS) {
				// we are near the NORTH_WEST corner
				wall_in_dir[7] = wall_in_dir[6] = wall_in_dir[5] = wall_in_dir[0] = wall_in_dir[1] = 1;
			} else {
				// we are near the WEST edge
				wall_in_dir[7] = wall_in_dir[6] = wall_in_dir[5] = 1;
			}
		} else {
			if (mc.edgeYMax != 0
					&& mc.cacheToWorldY(mc.edgeYMax) < curLoc.y
							+ RETREAT_RADIUS) {
				// we are near the SOUTH edge
				wall_in_dir[5] = wall_in_dir[4] = wall_in_dir[3] = 1;
			} else if (mc.edgeYMin != 0
					&& mc.cacheToWorldY(mc.edgeYMin) > curLoc.y
							- RETREAT_RADIUS) {
				// we are near the NORTH edge
				wall_in_dir[7] = wall_in_dir[0] = wall_in_dir[1] = 1;
			} else {
				// we are not near any wall or corner
			}
		}

		// dbg.setIndicatorString('y', 2, ""
		// +wall_in_dir[0]+wall_in_dir[1]+wall_in_dir[2]+wall_in_dir[3]
		// +wall_in_dir[4]+wall_in_dir[5]+wall_in_dir[6]+wall_in_dir[7]
		// +" "+mc.edgeXMax+" "+mc.edgeXMin+" "+mc.edgeYMax+" "+mc.edgeYMin+" "+mc.cacheToWorldX(mc.edgeXMax));

		if (lastFlee != null)
			wall_in_dir[lastFlee.opposite().ordinal()] = 1;

		String dir = ""
				.concat(closest_in_dir[0] == 99 ? (wall_in_dir[0] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[1] == 99 ? (wall_in_dir[1] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[2] == 99 ? (wall_in_dir[2] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[3] == 99 ? (wall_in_dir[3] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[4] == 99 ? (wall_in_dir[4] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[5] == 99 ? (wall_in_dir[5] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[6] == 99 ? (wall_in_dir[6] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[7] == 99 ? (wall_in_dir[7] == 0 ? "o"
						: "x") : "x");
		dir = dir.concat(dir);
		int index;

		targetDir = curLoc.directionTo(target);

		Direction newdir;
		index = dir.indexOf("ooooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 3) % 8];
			if (newdir != targetDir || curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 3) % 8];
			if (newdir != targetDir || curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("ooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 2) % 8];
			if (newdir != targetDir || curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 2) % 8];
			if (newdir != targetDir || curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("ooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 1) % 8];
			if (newdir != targetDir || curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oo");
		if (index > -1) {
			newdir = Constants.directions[(index + 1) % 8];
			if (newdir != targetDir || curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("o");
		if (index > -1) {
			newdir = Constants.directions[(index) % 8];
			if (newdir != targetDir || curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		// dbg.println('y',"GONNTA GET GEE'D");
		int lowest = closest_in_dir[0];
		int lowesti = 0;
		for (int x = 1; x < 8; x++)
			if (closest_in_dir[x] < lowest) {
				lowesti = x;
				lowest = closest_in_dir[x];
			}
		// target = radar.getEnemySwarmTarget();
		// newdir = target.directionTo(curLoc);
		newdir = Constants.directions[lowesti];
		if (newdir != targetDir || curLoc.distanceSquaredTo(target) < 10) {
			lastFlee = targetDir = newdir;
			target = curLoc.add(targetDir, RETREAT_DISTANCE);
			return null;
		}
		return null;
	}

	private String computeRetreatTargetLoose() {
		lastPowerNodeGuess = null;
		int[] closest_in_dir = radar.closestInDir;
		int[] wall_in_dir = new int[8];

		// now, deal with when we are close to map boundaries
		if (mc.edgeXMax != 0
				&& mc.cacheToWorldX(mc.edgeXMax) < curLoc.x + RETREAT_RADIUS) {
			if (mc.edgeYMax != 0
					&& mc.cacheToWorldY(mc.edgeYMax) < curLoc.y
							+ RETREAT_RADIUS) {
				// we are near the SOUTH_EAST corner
				wall_in_dir[1] = wall_in_dir[2] = wall_in_dir[3] = wall_in_dir[4] = wall_in_dir[5] = 1;
			} else if (mc.edgeYMin != 0
					&& mc.cacheToWorldY(mc.edgeYMin) > curLoc.y
							- RETREAT_RADIUS) {
				// we are near the NORTH_EAST corner
				wall_in_dir[1] = wall_in_dir[2] = wall_in_dir[3] = wall_in_dir[0] = wall_in_dir[7] = 1;
			} else {
				// we are near the EAST edge
				wall_in_dir[1] = wall_in_dir[2] = wall_in_dir[3] = 1;
			}
		} else if (mc.edgeXMin != 0
				&& mc.cacheToWorldX(mc.edgeXMin) > curLoc.x - RETREAT_RADIUS) {
			if (mc.edgeYMax != 0
					&& mc.cacheToWorldY(mc.edgeYMax) < curLoc.y
							+ RETREAT_RADIUS) {
				// we are near the SOUTH_WEST corner
				wall_in_dir[7] = wall_in_dir[6] = wall_in_dir[5] = wall_in_dir[4] = wall_in_dir[3] = 1;
			} else if (mc.edgeYMin != 0
					&& mc.cacheToWorldY(mc.edgeYMin) > curLoc.y
							- RETREAT_RADIUS) {
				// we are near the NORTH_WEST corner
				wall_in_dir[7] = wall_in_dir[6] = wall_in_dir[5] = wall_in_dir[0] = wall_in_dir[1] = 1;
			} else {
				// we are near the WEST edge
				wall_in_dir[7] = wall_in_dir[6] = wall_in_dir[5] = 1;
			}
		} else {
			if (mc.edgeYMax != 0
					&& mc.cacheToWorldY(mc.edgeYMax) < curLoc.y
							+ RETREAT_RADIUS) {
				// we are near the SOUTH edge
				wall_in_dir[5] = wall_in_dir[4] = wall_in_dir[3] = 1;
			} else if (mc.edgeYMin != 0
					&& mc.cacheToWorldY(mc.edgeYMin) > curLoc.y
							- RETREAT_RADIUS) {
				// we are near the NORTH edge
				wall_in_dir[7] = wall_in_dir[0] = wall_in_dir[1] = 1;
			} else {
				// we are not near any wall or corner
			}
		}

		if (lastFlee != null)
			wall_in_dir[lastFlee.opposite().ordinal()] = 1;

		String dir = ""
				.concat(closest_in_dir[0] == 99 ? (wall_in_dir[0] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[1] == 99 ? (wall_in_dir[1] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[2] == 99 ? (wall_in_dir[2] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[3] == 99 ? (wall_in_dir[3] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[4] == 99 ? (wall_in_dir[4] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[5] == 99 ? (wall_in_dir[5] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[6] == 99 ? (wall_in_dir[6] == 0 ? "o"
						: "x") : "x")
				.concat(closest_in_dir[7] == 99 ? (wall_in_dir[7] == 0 ? "o"
						: "x") : "x");
		dir = dir.concat(dir);
		int index;

		targetDir = curLoc.directionTo(target);

		Direction newdir;
		index = dir.indexOf("ooooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 3) % 8];
			if (!isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 3) % 8];
			if (!isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("ooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 2) % 8];
			if (!isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 2) % 8];
			if (!isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("ooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 1) % 8];
			if (!isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oo");
		if (index > -1) {
			newdir = Constants.directions[(index + 1) % 8];
			if (!isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("o");
		if (index > -1) {
			newdir = Constants.directions[(index) % 8];
			if (!isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		int lowest = closest_in_dir[0];
		int lowesti = 0;
		for (int x = 1; x < 8; x++)
			if (closest_in_dir[x] < lowest) {
				lowesti = x;
				lowest = closest_in_dir[x];
			}
		newdir = Constants.directions[lowesti];
		if (!isAdjacent(newdir, targetDir)
				|| curLoc.distanceSquaredTo(target) < 10) {
			lastFlee = targetDir = newdir;
			target = curLoc.add(targetDir, RETREAT_DISTANCE);
			return null;
		}
		return null;
	}

	private String computeRetreatTarget2() {
		lastPowerNodeGuess = null;
		int[] closest_in_dir = radar.closestInDir;
		int[] wall_in_dir = new int[8];

		// now, deal with when we are close to map boundaries
		if (mc.edgeXMax != 0
				&& mc.cacheToWorldX(mc.edgeXMax) < curLoc.x + RETREAT_RADIUS) {
			// we are near the EAST edge
			wall_in_dir[2] = 1;
			if (mc.cacheToWorldX(mc.edgeXMax) < curLoc.x + RETREAT_RADIUS_CLOSE) {
				wall_in_dir[1] = wall_in_dir[3] = 1;
			}
		}

		if (mc.edgeXMin != 0
				&& mc.cacheToWorldX(mc.edgeXMin) > curLoc.x - RETREAT_RADIUS) {
			// we are near the WEST edge
			wall_in_dir[6] = 1;
			if (mc.cacheToWorldX(mc.edgeXMin) > curLoc.x - RETREAT_RADIUS_CLOSE) {
				wall_in_dir[7] = wall_in_dir[5] = 1;
			}
		}

		if (mc.edgeYMax != 0
				&& mc.cacheToWorldY(mc.edgeYMax) < curLoc.y + RETREAT_RADIUS) {
			// we are near the SOUTH edge
			wall_in_dir[4] = 1;
			if (mc.cacheToWorldY(mc.edgeYMax) < curLoc.y + RETREAT_RADIUS_CLOSE) {
				wall_in_dir[3] = wall_in_dir[5] = 1;
			}
		}

		if (mc.edgeYMin != 0
				&& mc.cacheToWorldY(mc.edgeYMin) > curLoc.y - RETREAT_RADIUS) {
			// we are near the NORTH edge
			wall_in_dir[0] = 1;
			if (mc.cacheToWorldY(mc.edgeYMin) > curLoc.y - RETREAT_RADIUS_CLOSE) {
				wall_in_dir[1] = wall_in_dir[7] = 1;
			}
		}

		wall_in_dir[curLoc.directionTo(radar.getEnemySwarmTarget()).ordinal()] = 1;

		String dir = "".concat((wall_in_dir[0] == 0 ? "o" : "x"))
				.concat((wall_in_dir[1] == 0 ? "o" : "x"))
				.concat((wall_in_dir[2] == 0 ? "o" : "x"))
				.concat((wall_in_dir[3] == 0 ? "o" : "x"))
				.concat((wall_in_dir[4] == 0 ? "o" : "x"))
				.concat((wall_in_dir[5] == 0 ? "o" : "x"))
				.concat((wall_in_dir[6] == 0 ? "o" : "x"))
				.concat((wall_in_dir[7] == 0 ? "o" : "x"));
		dir = dir.concat(dir);
		int index;

		targetDir = curLoc.directionTo(target);

		Direction newdir;
		index = dir.indexOf("ooooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 3) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 3) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("ooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 2) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 2) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("ooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 1) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		if (radar.numAllyFighters > 0) {
			int n = 0;
			int d = 0;
			for (int x = 0; x < 8; x++) {
				n += radar.allies_in_dir[x];
				d += x * radar.allies_in_dir[x];
			}
			d = d / n;
			newdir = Constants.directions[(d) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		// Not good
		int lowest = closest_in_dir[0];
		int lowesti = 0;
		for (int x = 1; x < 8; x++)
			if (closest_in_dir[x] < lowest) {
				lowesti = x;
				lowest = closest_in_dir[x];
			}
		newdir = Constants.directions[lowesti];
		if (!isAdjacent(newdir, targetDir)
				|| curLoc.distanceSquaredTo(target) < 10) {
			lastFlee = targetDir = newdir;
			target = curLoc.add(targetDir, RETREAT_DISTANCE);
			return null;
		}
		return null;
	}

	private String computeRetreatTarget3() {
		// alright, brute force method - find square which gets us the farthest
		// from all enemies

		double bestdist = 0;
		int bestdir = -1;
		double curdist = 0;
		int count = radar.numEnemyRobots - radar.numEnemyScouts
				- radar.numEnemyTowers;
		RobotInfo ri;
		double[] dists = new double[count];
		int[] dirs = new int[count];
		int xx = 0;
		Direction newdir;

		int[] wall_in_dir = new int[8];

		if (mc.edgeXMax != 0)
			wall_in_dir[2] = mc.cacheToWorldX(mc.edgeXMax) - curLoc.x;
		else
			wall_in_dir[2] = 99;

		if (mc.edgeYMax != 0)
			wall_in_dir[4] = mc.cacheToWorldY(mc.edgeYMax) - curLoc.y;
		else
			wall_in_dir[4] = 99;

		if (mc.edgeXMin != 0)
			wall_in_dir[6] = curLoc.x - mc.cacheToWorldX(mc.edgeXMin);
		else
			wall_in_dir[6] = 99;

		if (mc.edgeYMin != 0)
			wall_in_dir[0] = curLoc.y - mc.cacheToWorldY(mc.edgeYMin);
		else
			wall_in_dir[0] = 99;

		wall_in_dir[1] = Math.min(wall_in_dir[0], wall_in_dir[2]);
		wall_in_dir[3] = Math.min(wall_in_dir[2], wall_in_dir[4]);
		wall_in_dir[5] = Math.min(wall_in_dir[4], wall_in_dir[6]);
		wall_in_dir[7] = Math.min(wall_in_dir[6], wall_in_dir[0]);

		// first calculate the current distance
		for (int x = 0; x < radar.numEnemyRobots; x++) {
			ri = radar.enemyInfos[radar.enemyRobots[x]];
			if (ri.type == RobotType.SCOUT || ri.type == RobotType.TOWER)
				continue;
			dists[xx] = Math.sqrt(curLoc.distanceSquaredTo(ri.location));
			dirs[xx] = curLoc.directionTo(ri.location).ordinal();
			xx++;
		}

		double dist = 0;
		int cc = count;

		for (int x = 0; x < count; x++)
			dist += dists[x];

		bestdist = curdist = dist / cc;

		// int w1,w2,w3,w4;
		for (int x = 0; x < 8; x++) {
			// if (!rc.canMove(Constants.directions[x])) continue;
			if (wall_in_dir[x] < 3)
				continue;
			dist = 0;
			cc = count;
			for (int y = 0; y < count; y++) {
				switch ((x - dirs[y] + 8) % 8) {
				case 0:
					dist += dists[y] - 1;
					break;
				case 1:
					dist += dists[y] - 0.5;
					break;
				case 2:
					dist += dists[y] + 0.1;
					break;
				case 3:
					dist += dists[y] + 0.6;
					break;
				case 4:
					dist += dists[y] + 1;
					break;
				case 5:
					dist += dists[y] + 0.6;
					break;
				case 6:
					dist += dists[y] + 0.1;
					break;
				case 7:
					dist += dists[y] - 0.5;
					break;
				}
			}

			dist = dist / cc;
			if (dist > bestdist) {
				bestdist = dist;
				bestdir = x;
			}
		}

		if (bestdir == -1) {
			// crap, nowhere to run, run to nearest archon
			newdir = curLoc.directionTo(dc.getClosestArchon());
		} else {
			newdir = Constants.directions[bestdir];
		}

		if (lastFlee == null || !isAdjacent(newdir, lastFlee)
				|| curLoc.distanceSquaredTo(target) < 10) {
			lastFlee = targetDir = newdir;
			target = curLoc.add(targetDir, RETREAT_DISTANCE);
			return null;
		}

		return "";
	}

	private String computeRetreatTarget4() {
		lastPowerNodeGuess = null;
		int[] closest_in_dir = radar.closestInDir;
		int[] wall_in_dir = new int[8];
		int[] s = new int[8];
		int[] score_aggregate = new int[8];
		int[] movable = new int[8];
		int dd;

		// now, deal with when we are close to map boundaries
		if (mc.edgeXMax != 0
				&& mc.cacheToWorldX(mc.edgeXMax) < curLoc.x + RETREAT_RADIUS) {
			// we are near the EAST edge
			wall_in_dir[2] = 1;
			movable[2] = 1;
			if (mc.cacheToWorldX(mc.edgeXMax) < curLoc.x + RETREAT_RADIUS_CLOSE) {
				wall_in_dir[1] = wall_in_dir[3] = 1;
				wall_in_dir[2] = 2;
				movable[1] = movable[2] = movable[3] = 1;
			}
		}

		if (mc.edgeXMin != 0
				&& mc.cacheToWorldX(mc.edgeXMin) > curLoc.x - RETREAT_RADIUS) {
			// we are near the WEST edge
			wall_in_dir[6] = 1;
			movable[6] = 1;
			if (mc.cacheToWorldX(mc.edgeXMin) > curLoc.x - RETREAT_RADIUS_CLOSE) {
				wall_in_dir[7] = wall_in_dir[5] = 1;
				wall_in_dir[6] = 2;
				movable[5] = movable[6] = movable[7] = 1;
			}
		}

		if (mc.edgeYMax != 0
				&& mc.cacheToWorldY(mc.edgeYMax) < curLoc.y + RETREAT_RADIUS) {
			// we are near the SOUTH edge
			wall_in_dir[4] = 1;
			movable[4] = 1;
			if (mc.cacheToWorldY(mc.edgeYMax) < curLoc.y + RETREAT_RADIUS_CLOSE) {
				wall_in_dir[3] = wall_in_dir[5] = 1;
				wall_in_dir[4] = 2;
				movable[3] = movable[4] = movable[5] = 1;
			}
		}

		if (mc.edgeYMin != 0
				&& mc.cacheToWorldY(mc.edgeYMin) > curLoc.y - RETREAT_RADIUS) {
			// we are near the NORTH edge
			wall_in_dir[0] = 1;
			movable[0] = 1;
			if (mc.cacheToWorldY(mc.edgeYMin) > curLoc.y - RETREAT_RADIUS_CLOSE) {
				wall_in_dir[1] = wall_in_dir[7] = 1;
				wall_in_dir[0] = 2;
				movable[7] = movable[0] = movable[1] = 1;
			}
		}

		s[0] = wall_in_dir[0] + radar.numEnemyInDir[0] - radar.allies_in_dir[0];
		s[1] = wall_in_dir[1] + radar.numEnemyInDir[1] - radar.allies_in_dir[1];
		s[2] = wall_in_dir[2] + radar.numEnemyInDir[2] - radar.allies_in_dir[2];
		s[3] = wall_in_dir[3] + radar.numEnemyInDir[3] - radar.allies_in_dir[3];
		s[4] = wall_in_dir[4] + radar.numEnemyInDir[4] - radar.allies_in_dir[4];
		s[5] = wall_in_dir[5] + radar.numEnemyInDir[5] - radar.allies_in_dir[5];
		s[6] = wall_in_dir[6] + radar.numEnemyInDir[6] - radar.allies_in_dir[6];
		s[7] = wall_in_dir[7] + radar.numEnemyInDir[7] - radar.allies_in_dir[7];

		score_aggregate[0] = s[6] + s[7] + s[0] + s[1] + s[2];
		score_aggregate[1] = s[3] + s[7] + s[0] + s[1] + s[2];
		score_aggregate[2] = s[3] + s[4] + s[0] + s[1] + s[2];
		score_aggregate[3] = s[3] + s[4] + s[5] + s[1] + s[2];
		score_aggregate[4] = s[3] + s[4] + s[5] + s[6] + s[2];
		score_aggregate[5] = s[3] + s[4] + s[5] + s[6] + s[7];
		score_aggregate[6] = s[0] + s[4] + s[5] + s[6] + s[7];
		score_aggregate[7] = s[0] + s[1] + s[5] + s[6] + s[7];

		int min = 9999;
		if (movable[0] == 0 && score_aggregate[0] < min)
			min = score_aggregate[0];
		if (movable[1] == 0 && score_aggregate[1] < min)
			min = score_aggregate[1];
		if (movable[2] == 0 && score_aggregate[2] < min)
			min = score_aggregate[2];
		if (movable[3] == 0 && score_aggregate[3] < min)
			min = score_aggregate[3];
		if (movable[4] == 0 && score_aggregate[4] < min)
			min = score_aggregate[4];
		if (movable[5] == 0 && score_aggregate[5] < min)
			min = score_aggregate[5];
		if (movable[6] == 0 && score_aggregate[6] < min)
			min = score_aggregate[6];
		if (movable[7] == 0 && score_aggregate[7] < min)
			min = score_aggregate[7];

		String dir = "".concat((score_aggregate[0] == min ? "o" : "x"))
				.concat((score_aggregate[1] == min ? "o" : "x"))
				.concat((score_aggregate[2] == min ? "o" : "x"))
				.concat((score_aggregate[3] == min ? "o" : "x"))
				.concat((score_aggregate[4] == min ? "o" : "x"))
				.concat((score_aggregate[5] == min ? "o" : "x"))
				.concat((score_aggregate[6] == min ? "o" : "x"))
				.concat((score_aggregate[7] == min ? "o" : "x"));
		dir = dir.concat(dir);
		int index;

		targetDir = curLoc.directionTo(target);

		Direction newdir;
		index = dir.indexOf("ooooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 3) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 3) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("ooooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 2) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 2) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("ooo");
		if (index > -1) {
			newdir = Constants.directions[(index + 1) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("oo");
		if (index > -1) {
			newdir = Constants.directions[(index + 1) % 8];
			if (!isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		index = dir.indexOf("o");
		if (index > -1) {
			newdir = Constants.directions[(index) % 8];
			if (!isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		if (radar.numAllyFighters > 0) {
			int n = 0;
			int d = 0;
			for (int x = 0; x < 8; x++) {
				n += radar.allies_in_dir[x];
				d += x * radar.allies_in_dir[x];
			}
			d = d / n;
			newdir = Constants.directions[(d) % 8];
			if (lastFlee == null || !isAdjacent(newdir, targetDir)
					|| curLoc.distanceSquaredTo(target) < 10) {
				lastFlee = targetDir = newdir;
				target = curLoc.add(targetDir, RETREAT_DISTANCE);
			}
			return dir;
		}

		// Uh oh
		int lowest = closest_in_dir[0];
		int lowesti = 0;
		for (int x = 1; x < 8; x++)
			if (closest_in_dir[x] < lowest) {
				lowesti = x;
				lowest = closest_in_dir[x];
			}
		newdir = Constants.directions[lowesti];
		if (!isAdjacent(newdir, targetDir)
				|| curLoc.distanceSquaredTo(target) < 10) {
			lastFlee = targetDir = newdir;
			target = curLoc.add(targetDir, RETREAT_DISTANCE);
			return null;
		}
		return null;
	}

	private boolean isAdjacent(Direction d1, Direction d2) {
		return (d1.ordinal() - d2.ordinal() + 9) % 8 < 2;
	}

	private void updateRetreatTarget() {
		if (curLoc.distanceSquaredTo(target) < 10) {
			target = curLoc.add(targetDir, RETREAT_DISTANCE);
		}
	}

	private void projectTargetOntoMap(Direction d) {

	}

	private void projectTargetOntoMap() {
		while (mc.isWall(target)) {
			target = target.add(target.directionTo(curLoc));
		}

		int x = target.x;
		int y = target.y;
		// now, deal with when we are close to map boundaries
		if (mc.edgeXMax != 0 && mc.cacheToWorldX(mc.edgeXMax) <= x) {
			// we are near the EAST edge
			x = mc.cacheToWorldX(mc.edgeXMax) - 1;
		}

		if (mc.edgeXMin != 0 && mc.cacheToWorldX(mc.edgeXMin) >= x) {
			// we are near the WEST edge
			x = mc.cacheToWorldX(mc.edgeXMin) + 1;
		}

		if (mc.edgeYMax != 0 && mc.cacheToWorldY(mc.edgeYMax) <= y) {
			// we are near the SOUTH edge
			y = mc.cacheToWorldY(mc.edgeYMax) - 1;
		}

		if (mc.edgeYMin != 0 && mc.cacheToWorldY(mc.edgeYMin) >= y) {
			// we are near the NORTH edge
			y = mc.cacheToWorldY(mc.edgeYMin) + 1;
		}
		target = new MapLocation(x, y);

		while (mc.isWall(target)) {
			target = target.add(target.directionTo(curLoc));
		}
	}

	private void computeBattleTarget() {
		target = radar.getEnemySwarmTarget();
		targetDir = curLoc.directionTo(target);
	}

	private void computeExploreTarget() {
		MapLocation t = mc.guessEnemyPowerCoreLocation();
		if (t.equals(lastPowerNodeGuess)) {
			if (curLoc.distanceSquaredTo(target) < 20
					&& mc.getEnemyPowerCoreLocation() == null) {
				switch (myHome.directionTo(curLoc)) {
				case NORTH: {
					if (mc.edgeYMin == 0)
						target = curLoc.add(Direction.NORTH, 10);
					else if (mc.edgeXMax == 0)
						target = curLoc.add(Direction.EAST, 10);
					else if (mc.edgeXMin == 0)
						target = curLoc.add(Direction.WEST, 10);
					else if (mc.edgeYMax == 0)
						target = curLoc.add(Direction.SOUTH, 10);
				}
					break;
				case NORTH_EAST: {
					if (mc.edgeYMin == 0)
						target = curLoc.add(Direction.NORTH, 10);
					else if (mc.edgeXMax == 0)
						target = curLoc.add(Direction.EAST, 10);
					else if (mc.edgeXMin == 0)
						target = curLoc.add(Direction.WEST, 10);
					else if (mc.edgeYMax == 0)
						target = curLoc.add(Direction.SOUTH, 10);
				}
					break;
				case EAST: {
					if (mc.edgeXMax == 0)
						target = curLoc.add(Direction.EAST, 10);
					else if (mc.edgeYMin == 0)
						target = curLoc.add(Direction.NORTH, 10);
					else if (mc.edgeYMax == 0)
						target = curLoc.add(Direction.SOUTH, 10);
					else if (mc.edgeXMin == 0)
						target = curLoc.add(Direction.WEST, 10);
				}
					break;
				case SOUTH_EAST: {
					if (mc.edgeXMax == 0)
						target = curLoc.add(Direction.EAST, 10);
					else if (mc.edgeYMax == 0)
						target = curLoc.add(Direction.SOUTH, 10);
					else if (mc.edgeYMin == 0)
						target = curLoc.add(Direction.NORTH, 10);
					else if (mc.edgeXMin == 0)
						target = curLoc.add(Direction.WEST, 10);
				}
					break;
				case SOUTH: {
					if (mc.edgeYMax == 0)
						target = curLoc.add(Direction.SOUTH, 10);
					else if (mc.edgeXMin == 0)
						target = curLoc.add(Direction.WEST, 10);
					else if (mc.edgeXMax == 0)
						target = curLoc.add(Direction.EAST, 10);
					else if (mc.edgeYMin == 0)
						target = curLoc.add(Direction.NORTH, 10);
				}
					break;
				case SOUTH_WEST: {
					if (mc.edgeYMax == 0)
						target = curLoc.add(Direction.SOUTH, 10);
					else if (mc.edgeXMin == 0)
						target = curLoc.add(Direction.WEST, 10);
					else if (mc.edgeXMax == 0)
						target = curLoc.add(Direction.EAST, 10);
					else if (mc.edgeYMin == 0)
						target = curLoc.add(Direction.NORTH, 10);
				}
					break;
				case WEST: {
					if (mc.edgeXMin == 0)
						target = curLoc.add(Direction.WEST, 10);
					else if (mc.edgeYMax == 0)
						target = curLoc.add(Direction.SOUTH, 10);
					else if (mc.edgeYMin == 0)
						target = curLoc.add(Direction.NORTH, 10);
					else if (mc.edgeXMax == 0)
						target = curLoc.add(Direction.EAST, 10);
				}
					break;
				case NORTH_WEST: {
					if (mc.edgeXMin == 0)
						target = curLoc.add(Direction.WEST, 10);
					else if (mc.edgeYMin == 0)
						target = curLoc.add(Direction.NORTH, 10);
					else if (mc.edgeYMax == 0)
						target = curLoc.add(Direction.SOUTH, 10);
					else if (mc.edgeXMax == 0)
						target = curLoc.add(Direction.EAST, 10);
				}
					break;
				}
			}
		} else {
			lastPowerNodeGuess = target = t;
		}

	}

	//We need to keep Archons at least 16 apart to allow for escape and so that
	// they aren't taken out by devastation force (AOE, etc)
	//TODO: This should be thrown into a constant for when attack distances change
	private Direction getDirAwayFromAlliedArchons(int minKeepApartDistSquared) {
		boolean[] movable = dc.getMovableDirections();
		Direction bestDir = null;
		int bestDist = 0;
		for (int i = 8; i >= 0; i--) {
			if (i != 8 && !movable[i])
				continue;
			Direction dir = i == 8 ? null : Constants.directions[i];
			MapLocation newLoc = i == 8 ? curLoc : curLoc.add(dir);
			int dist = Integer.MAX_VALUE;
			for (MapLocation loc : dc.getAlliedArchons()) {
				if (loc.equals(curLoc))
					continue;
				dist = Math.min(dist, loc.distanceSquaredTo(newLoc));
			}
			if (bestDist <= minKeepApartDistSquared && dist > bestDist) {
				bestDist = dist;
				bestDir = dir;
			}
		}
		return bestDir;
	}

	//Location of power node adjacent to main power core
	private MapLocation getNextPowerNodeAdjacentToCore() {
		PowerNode[] nodes = dc.getAlliedPowerNodes();
		for (int i = 0; i < neighborsOfPowerCore.length; i++) {
			MapLocation loc = neighborsOfPowerCore[i];
			if (mc.isDeadEndPowerNode(loc))
				continue;
			boolean flag = false;
			for (int j = 0; j < nodes.length; j++) {
				if (loc.equals(nodes[j].getLocation())) {
					flag = true;
					break;
				}
			}
			if (!flag)
				return loc;
		}
		return null;
	}

	//If it's possible to capture the note, then returns true
	private boolean isCapturableNode(MapLocation loc) {
		MapLocation[] locs = dc.getCapturablePowerCores();
		for (MapLocation x : locs)
			if (x.equals(loc))
				return true;
		return false;
	}

	public void initSpawnProbabilities() {
		scoutRatio = spawnScoutProb = 0.07;
		spawnDisrupterProb = 0.0;
		soldierDisrupterRatio = 0.3;
	}

	public void updateSoldierDisruptorRatio() {
		int total = (tmem.curScorcherCount + tmem.curDisrupterCount + tmem.curSoldierCount);
		if (total == 0)
			soldierDisrupterRatio = 0.3;
		else
			soldierDisrupterRatio = (tmem.curScorcherCount / total) * 0.4 + 0.1;
	}

	public void updateScoutProb() {
		scoutRatio = 0.07 - radar.numAllyScouts * 0.03;
	}

	public void updateSpawnProbabilitiesForRound() {
		if (curRound < 1000) {
			spawnScoutProb = 0.0;
			spawnDisrupterProb = 0.0;
		} else if (behavior == BehaviorState.BATTLE) {
			// spawnScoutProb = 0.07;
			spawnScoutProb = scoutRatio;
			spawnDisrupterProb = soldierDisrupterRatio;
		} else if (curRound < 2000) {
			// spawnScoutProb = 0.07;
			spawnScoutProb = scoutRatio;
			spawnDisrupterProb = 0.0;
		} else {
			// spawnScoutProb = 0.05;
			spawnScoutProb = scoutRatio * 5 / 7;
			// spawnDisrupterProb = 0.3;
			spawnDisrupterProb = soldierDisrupterRatio;
		}

		if (curRound < 2000 && curRound > 1000) {
			spawnScoutProb = scoutRatio * 2;
		}

	}

	private RobotType getNextUnitToSpawn() {
		updateSpawnProbabilitiesForRound();
		if (Util.randDouble() < spawnScoutProb)
			return RobotType.SCOUT;
		if (Util.randDouble() < spawnDisrupterProb)
			return RobotType.DISRUPTER;
		return RobotType.SOLDIER;
	}

	private RobotType getNextUnitToSpawnTeam16() {
		updateSpawnProbabilitiesForRound();
		return (Util.randDouble() < spawnScoutProb) ? RobotType.SCOUT
				: RobotType.SOLDIER;
	}
}
