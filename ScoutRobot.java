package tcwolf;

import battlecode.common.Clock;
import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.Message;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.RobotType;

public class ScoutRobot extends BaseRobot {

	private enum StrategyState {
		INITIAL_EXPLORE, BATTLE, SUPPORT,
	}

	private enum BehaviorState {
		LOOK_FOR_MAP_EDGE, REPORT_TO_ARCHON, SCOUT_FOR_ENEMIES, SUPPORT_FRONT_LINES, SENDING_ALLY_FLUX, PET, HARASS, HIBERNATE, LOW_FLUX_HIBERNATE,
	}

	private StrategyState lastStrategy;
	private StrategyState strategy;
	private BehaviorState behavior;
	private MapLocation objective;

	MapLocation enemySpottedTarget;
	int enemySpottedRound;

	MapLocation closestEnemyLocation;
	RobotType closestEnemyType;

	MapLocation helpAllyLocation = null;
	int helpAllyRound;

	Direction mapEdgeToSeek;
	boolean doneWithInitialScout;
	Direction lastRetreatDir;
	boolean shouldCalcAlly;

	private final static int RETREAT_RADIUS = 3;
	private final static int ROUNDS_TO_EXPLORE = 500;
	private final static double REGENRATION_FLUX_THRESHOLD = 3.0;

	private final static double THRESHOLD_TO_HEAL_ALLIES = 0.9;
	private final static double THRESHOLD_TO_REFUEL_ALLIES = 30;

	public ScoutRobot(RobotController myRC) throws GameActionException {
		super(myRC);
		enemySpottedTarget = null;
		enemySpottedRound = -55555;
		mapEdgeToSeek = null;
		fbs.setPoolMode();
		nav.setNavigationMode(NavigationMode.GREEDY);
		io.setChannels(new BroadcastChannel[] { BroadcastChannel.ALL,
				BroadcastChannel.SCOUTS, BroadcastChannel.EXPLORERS });
		strategy = StrategyState.INITIAL_EXPLORE;
		doneWithInitialScout = false;
		lastRetreatDir = null;
		shouldCalcAlly = false;
		// if we found out the enemy team in a previous round, recall it
		if (tmem.getEnemyTeam() != 0) {
			// dbg.println('e', "Team memory says we're playing vs team " +
			// tmem.getEnemyTeam());
			mas.assertEnemyTeam(tmem.getEnemyTeam());
		}
		resetBehavior();
	}

	@Override
	public void run() throws GameActionException {

		shouldCalcAlly = false;
		// scan every round in all conditions except support
		if (strategy != StrategyState.SUPPORT || curRound % 2 == 0) {
			radar.scan(true, true);
			if (radar.closestEnemy != null) {
				if (radar.closestEnemy.type == RobotType.ARCHON
						|| radar.closestEnemy.type == RobotType.TOWER
						|| radar.closestEnemy.flux > 0.15) {
					enemySpottedTarget = radar.closestEnemy.location;
					enemySpottedRound = curRound;
				}

			} else {
				enemySpottedTarget = null;
			}
		}

		// report enemy info in all conditions
		if (curRound % 5 == myID % 5)
			radar.broadcastEnemyInfo(false);

		// Setup strategy transitions
		if (!doneWithInitialScout && Clock.getRoundNum() < ROUNDS_TO_EXPLORE) {
			strategy = StrategyState.INITIAL_EXPLORE;
		} else if (radar.numEnemyScouts < radar.numEnemyRobots) {
			strategy = StrategyState.BATTLE;
		} else {
			strategy = StrategyState.SUPPORT;
		}
		if (lastStrategy != strategy) {
			resetBehavior();
		}
		lastStrategy = strategy;

		// logic for initial explore
		if (strategy == StrategyState.INITIAL_EXPLORE) {

			if (behavior == BehaviorState.SCOUT_FOR_ENEMIES) {
				if (radar.closestEnemy != null
						&& radar.closestEnemy.type != RobotType.SCOUT) {
					doneWithInitialScout = true;
					behavior = BehaviorState.REPORT_TO_ARCHON;
				}
			} else if (behavior == BehaviorState.REPORT_TO_ARCHON) {
				if (curLoc.distanceSquaredTo(dc.getClosestArchon()) <= 25) {
					resetBehavior();
				}
			} else if (behavior == BehaviorState.LOOK_FOR_MAP_EDGE) {
				if (mapEdgeToSeek == Direction.NORTH && mc.edgeYMin != 0
						|| mapEdgeToSeek == Direction.SOUTH && mc.edgeYMax != 0
						|| mapEdgeToSeek == Direction.WEST && mc.edgeXMin != 0
						|| mapEdgeToSeek == Direction.EAST && mc.edgeXMax != 0)
					behavior = BehaviorState.REPORT_TO_ARCHON;
			}
		}

		// flux support logic
		if (helpAllyLocation != null) {
			if (curLoc.distanceSquaredTo(helpAllyLocation) <= 2) {
				helpAllyLocation = null; // we should have healed him
				if (behavior == BehaviorState.SENDING_ALLY_FLUX)
					resetBehavior();
			}
		}

		// fast behavior switch if we're going to get G'ed
		if (rc.getFlux() < 15 || rc.getEnergon() < 7) {
			behavior = BehaviorState.REPORT_TO_ARCHON;
		} else if (curLoc.distanceSquaredTo(dc.getClosestArchon()) < 2) {
			resetBehavior();
		}

		// received flux from ally
		if (behavior == BehaviorState.PET) {
			if (rc.getFlux() > 40)
				resetBehavior();
		}

		// if we're in battle mode, check behavior every turn
		if (strategy == StrategyState.BATTLE
				&& behavior != BehaviorState.REPORT_TO_ARCHON) {
			// if there are no enemy soldiers or disrupters around and an enemy
			// archon or scorcher is in range, harass them...
			if (radar.numEnemySoldiers + radar.numEnemyDisruptors == 0
					&& radar.numEnemyArchons + radar.numEnemyScorchers > 0) {
				behavior = BehaviorState.HARASS;

				// ... otherwise, help your friends!
			} else {
				behavior = BehaviorState.SUPPORT_FRONT_LINES;
			}
		}

		// set objective based on behavior
		switch (behavior) {
		case SENDING_ALLY_FLUX:
			objective = helpAllyLocation;
			break;
		case SCOUT_FOR_ENEMIES:
			objective = mc.guessEnemyPowerCoreLocation();
			break;
		case REPORT_TO_ARCHON:
			objective = dc.getClosestArchon();
			break;
		case PET:
			// objective = dc.getClosestArchon();
			// petSupport();
			supportFrontline();
			break;
		case SUPPORT_FRONT_LINES:
			supportFrontline();
			break;
		case HARASS:
			// all targets in this mode are temporary so we don't set objective
		default:
			break;
		}

		if (objective == null)
			objective = curLoc;

		// attack if you can
		if (!rc.isAttackActive() && radar.closestEnemyDist <= 5) {
			RobotInfo bestInfo = null;
			double bestValue = 0;
			for (int n = radar.numEnemyRobots; --n >= 0;) {
				RobotInfo ri = radar.enemyInfos[radar.enemyRobots[n]];
				if (!rc.canAttackSquare(ri.location))
					continue;
				if (ri.flux > bestValue) {
					bestInfo = ri;
					bestValue = ri.flux;
				}
			}

			if (bestValue >= 0.15) {
				rc.attackSquare(bestInfo.location, bestInfo.type.level);
			}
		}

		// heal if you should
		if (rc.getFlux() > REGENRATION_FLUX_THRESHOLD
				&& ((curEnergon < myMaxEnergon - 0.2) || radar.numAllyToRegenerate > 0)) {
			rc.regenerate();
		}

		// if we just found out the enemy team this round, broadcast the enemy
		// team
		if (tmem.getEnemyTeam() == 0 && mas.guessEnemyTeam() != -1
				&& curRound % 20 == (myID + 10) % 20) {
			io.sendUShort(BroadcastChannel.EXPLORERS,
					BroadcastType.GUESS_ENEMY_TEAM, mas.guessEnemyTeam());
		}

		// perform message attack every few rounds if possible
		if (mas.isLoaded() && curRound % 20 == myID % 20) {
			Message m = mas.getEnemyMessage();
			if (m != null) {
				io.forceSend(mas.getEnemyMessage());
			}
		}

		// broadcast enemy spotting
		if (curRound % 4 == myID % 4
				&& behavior == BehaviorState.REPORT_TO_ARCHON
				&& curLoc.distanceSquaredTo(dc.getClosestArchon()) <= 64) {
			if (enemySpottedTarget != null)
				io.sendUShorts(BroadcastChannel.ALL,
						BroadcastType.ENEMY_SPOTTED, new int[] {
								enemySpottedRound, enemySpottedTarget.x,
								enemySpottedTarget.y });
		}

		// indicator strings
		// dbg.setIndicatorString('e', 1,
		// "Target="+locationToVectorString(objective)+
		// ", Strat=" + strategy + ", Behavior="+behavior);
		// dbg.setIndicatorString('y', 2,
		// "flux:"+radar.lowestFlux+" "+(radar.lowestFluxAllied!=null?radar.lowestFluxAllied.location:null)
		// +" energon:"+radar.lowestEnergonRatio+" "+(radar.lowestEnergonAllied!=null?radar.lowestEnergonAllied.location:null));
	}

	private void resetBehavior() {
		switch (strategy) {
		case INITIAL_EXPLORE:
			if (birthday % 10 < 5) {
				if (mc.edgeXMax == 0) {
					behavior = BehaviorState.LOOK_FOR_MAP_EDGE;
					mapEdgeToSeek = Direction.EAST;
				} else if (mc.edgeXMin == 0) {
					behavior = BehaviorState.LOOK_FOR_MAP_EDGE;
					mapEdgeToSeek = Direction.WEST;
				} else {
					behavior = BehaviorState.SCOUT_FOR_ENEMIES;
				}
			} else {
				if (mc.edgeYMax == 0) {
					behavior = BehaviorState.LOOK_FOR_MAP_EDGE;
					mapEdgeToSeek = Direction.SOUTH;
				} else if (mc.edgeYMin == 0) {
					behavior = BehaviorState.LOOK_FOR_MAP_EDGE;
					mapEdgeToSeek = Direction.NORTH;
				} else {
					behavior = BehaviorState.SCOUT_FOR_ENEMIES;
				}
			}
			break;
		case BATTLE:
			// these behaviors are set every turn in the run method
			break;
		case SUPPORT:
			if (helpAllyLocation != null)
				behavior = BehaviorState.SENDING_ALLY_FLUX;
			else
				behavior = BehaviorState.PET;
			break;
		default:
			break;
		}

	}

	private void petSupport() {
		// old logic
		// if(closestEnemyLocation != null)
		// objective = closestEnemyLocation;
		// else if(enemySpottedTarget != null)
		// objective = enemySpottedTarget;
		// else
		// objective = dc.getClosestArchon();

		// 1. find unit with lowest flux and go there
		// (code done in radar)
		if (radar.lowestFlux < THRESHOLD_TO_REFUEL_ALLIES) {
			objective = radar.lowestFluxAllied.location;
		} else if (radar.lowestEnergonRatio < THRESHOLD_TO_HEAL_ALLIES) {
			objective = radar.lowestEnergonAllied.location;
		} else if (closestEnemyLocation != null) {
			objective = closestEnemyLocation;
		} else if (enemySpottedTarget != null) {
			objective = enemySpottedTarget;
		} else {
			objective = dc.getClosestArchon();
		}
	}

	private void supportFrontline() {
		// old logic
		// if(closestEnemyLocation != null)
		// objective = closestEnemyLocation;
		// else if(enemySpottedTarget != null)
		// objective = enemySpottedTarget;
		// else
		// objective = dc.getClosestArchon();

		// 1. find unit with lowest flux and go there
		// (code done in radar)
		if (radar.lowestEnergonRatio < THRESHOLD_TO_HEAL_ALLIES) {
			// objective = radar.lowestEnergonAllied.location;
			// objective = getBestRegenSquare();
			// objective = getBestRegenSquareFast();
			if (gameEndNow)
				objective = radar.lowestEnergonAllied.location;
			else
				shouldCalcAlly = true;
			// objective = getBestRegenSquareFastFull();
			if (objective == null) {
				objective = radar.lowestEnergonAllied.location;
			}
		} else if (radar.lowestFlux < THRESHOLD_TO_REFUEL_ALLIES) {
			objective = radar.lowestFluxAllied.location;
		} else if (closestEnemyLocation != null) {
			objective = closestEnemyLocation;
		} else if (enemySpottedTarget != null) {
			objective = enemySpottedTarget;
		} else {
			objective = dc.getClosestArchon();
		}
	}

	@Override
	public void processMessage(BroadcastType msgType, StringBuilder sb)
			throws GameActionException {
		if (!gameEndNow) {
			switch (msgType) {
			case LOW_FLUX_HELP:
				if (helpAllyLocation == null) {
					helpAllyLocation = BroadcastSystem.decodeSenderLoc(sb);
					helpAllyRound = curRound;
				}
				if (strategy == StrategyState.SUPPORT) { // go flux ally if
															// we're in support
															// mode
					behavior = BehaviorState.SENDING_ALLY_FLUX;
				}
				break;
			case MAP_EDGES:
				ses.receiveMapEdges(BroadcastSystem.decodeUShorts(sb));
				break;
			case POWERNODE_FRAGMENTS:
				ses.receivePowerNodeFragment(BroadcastSystem.decodeInts(sb));
				break;
			default:
				super.processMessage(msgType, sb);
			}
		}
	}

	@Override
	public MoveInfo computeNextMove() throws GameActionException {
		if (rc.getFlux() < 0.5)
			return null;

		if (shouldCalcAlly)
			objective = getBestRegenSquareFastFull();

		// if in harass mode, chase the nearest enemy archon or scorcher...
		if (behavior == BehaviorState.HARASS) {
			RobotInfo enemyToHarass = getClosestEnemyArchonScorcher();
			if (enemyToHarass == null) {
				// we shouldn't get here if our state transitions are happening
				// correctly!
				return new MoveInfo(nav.navigateGreedy(dc.getClosestArchon()),
						false);
			} else {
				return new MoveInfo(nav.navigateGreedy(enemyToHarass.location),
						false);
			}
		}

		// ...otherwise, ALWAYS RETREAT FROM ENEMY if in range
		else if (radar.closestEnemyWithFlux != null) {
			if (behavior == BehaviorState.LOOK_FOR_MAP_EDGE
					|| behavior == BehaviorState.REPORT_TO_ARCHON) {
				if (radar.closestEnemyDist <= 23) {
					// flee code
					Direction dir = getFullRetreatDir();
					// Direction dir =
					// curLoc.directionTo(radar.closestEnemyWithFlux.location).opposite();
					// Direction dir =
					// curLoc.directionTo(radar.getEnemySwarmCenter()).opposite();

					lastRetreatDir = dir;
					return new MoveInfo(dir, true);
				}
			}

			lastRetreatDir = null;
			int fleedist = 0;
			switch (radar.closestEnemyWithFlux.type) {
			case SOLDIER:
				if (radar.closestEnemyWithFlux.roundsUntilAttackIdle >= 4)
					fleedist = 10;
				else
					fleedist = 13;
				break;
			case DISRUPTER:
				if (radar.closestEnemyWithFlux.roundsUntilAttackIdle >= 4)
					fleedist = 16;
				else
					fleedist = 19;
				break;

			}

			if (radar.closestEnemyWithFluxDist <= fleedist) {
				// flee code
				Direction dir = getRetreatDir();
				// Direction dir =
				// curLoc.directionTo(radar.closestEnemyWithFlux.location).opposite();
				// Direction dir =
				// curLoc.directionTo(radar.getEnemySwarmCenter()).opposite();

				lastRetreatDir = dir;
				return new MoveInfo(dir, true);
			} else {
				lastRetreatDir = null;
				Direction target = null;
				// INITIAL_EXPLORE STATES
				if (behavior == BehaviorState.LOOK_FOR_MAP_EDGE)
					target = mapEdgeToSeek;
				else if (behavior == BehaviorState.SCOUT_FOR_ENEMIES) {
					if (radar.closestAllyScoutDist < 25)
						target = curLoc
								.directionTo(radar.closestAllyScout.location);
					else
						target = nav.navigateRandomly(objective);
				} else {
					target = curLoc.directionTo(objective);
				}

				if (curLoc.add(target).distanceSquaredTo(
						radar.closestEnemyWithFlux.location) <= fleedist)
					return null;
				else
					return new MoveInfo(target, false);
			}
		}

		lastRetreatDir = null;

		// INITIAL_EXPLORE STATES
		if (behavior == BehaviorState.LOOK_FOR_MAP_EDGE)
			return new MoveInfo(mapEdgeToSeek, false);
		else if (behavior == BehaviorState.SCOUT_FOR_ENEMIES) {
			if (radar.closestAllyScoutDist < 25)
				return new MoveInfo(curLoc.directionTo(
						radar.closestAllyScout.location).opposite(), false);
			else
				return new MoveInfo(nav.navigateRandomly(objective), false);
		}

		// keep away from allied scouts
		if (behavior != BehaviorState.REPORT_TO_ARCHON
				&& radar.closestAllyScoutDist < 10)
			return new MoveInfo(curLoc.directionTo(
					radar.closestAllyScout.location).opposite(), false);

		// Go to objective
		return new MoveInfo(curLoc.directionTo(objective), false);
	}

	@Override
	public void useExtraBytecodes() throws GameActionException {
		if (strategy == StrategyState.INITIAL_EXPLORE) {
			if (curRound == Clock.getRoundNum()
					&& Clock.getBytecodesLeft() > 6000
					&& Util.randDouble() < 0.05) {
				ses.broadcastMapFragment();
			}
			if (curRound == Clock.getRoundNum()
					&& Clock.getBytecodesLeft() > 5000
					&& Util.randDouble() < 0.05) {
				ses.broadcastPowerNodeFragment();
			}
			if (curRound == Clock.getRoundNum()
					&& Clock.getBytecodesLeft() > 2000
					&& Util.randDouble() < 0.05) {
				ses.broadcastMapEdges();
			}
		}
		super.useExtraBytecodes();

		// If we have identified the enemy team, remember the team in memory and
		// load past message data.
		// This method call will occur at most once per Scout.
		if (mas.guessEnemyTeam() != -1 && !mas.isLoaded()
				&& Clock.getBytecodesLeft() > 5000) {
			tmem.recordEnemyTeam(mas.guessEnemyTeam());
			// mos.rememberString("I think we are facing team " +
			// mas.guessEnemyTeam() + ".");
			mas.load();
		}
	}

	private Direction getRetreatDir() {
		// 7 0 1
		// 6 2
		// 5 4 3
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

		Direction newdir = curLoc
				.directionTo(radar.closestEnemyWithFlux.location);
		if (newdir.ordinal() < 8)
			wall_in_dir[newdir.ordinal()] = 1;

		String dir = "".concat(wall_in_dir[0] == 0 ? "o" : "x")
				.concat(wall_in_dir[1] == 0 ? "o" : "x")
				.concat(wall_in_dir[2] == 0 ? "o" : "x")
				.concat(wall_in_dir[3] == 0 ? "o" : "x")
				.concat(wall_in_dir[4] == 0 ? "o" : "x")
				.concat(wall_in_dir[5] == 0 ? "o" : "x")
				.concat(wall_in_dir[6] == 0 ? "o" : "x")
				.concat(wall_in_dir[7] == 0 ? "o" : "x");
		dir = dir.concat(dir);
		int index;

		index = dir.indexOf("ooooooo");
		if (index > -1)
			return Constants.directions[(index + 3) % 8];

		index = dir.indexOf("oooooo");
		if (index > -1)
			return Constants.directions[(index + 3) % 8];

		index = dir.indexOf("ooooo");
		if (index > -1)
			return Constants.directions[(index + 2) % 8];

		index = dir.indexOf("oooo");
		if (index > -1)
			return Constants.directions[(index + 2) % 8];

		index = dir.indexOf("ooo");
		if (index > -1)
			return Constants.directions[(index + 1) % 8];

		index = dir.indexOf("oo");
		if (index > -1)
			return Constants.directions[(index + 1) % 8];

		index = dir.indexOf("o");
		if (index > -1)
			return Constants.directions[(index) % 8];

		return newdir.opposite();
	}

	private Direction getFullRetreatDir() {
		// 7 0 1
		// 6 2
		// 5 4 3
		int[] wall_in_dir = new int[8];
		int[] closest_in_dir = radar.closestInDir;

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

		// Direction newdir =
		// curLoc.directionTo(radar.closestEnemyWithFlux.location);
		if (lastRetreatDir != null)
			wall_in_dir[lastRetreatDir.opposite().ordinal()] = 1;

		// String dir = "".concat(wall_in_dir[0]==0?"o":"x")
		// .concat(wall_in_dir[1]==0?"o":"x")
		// .concat(wall_in_dir[2]==0?"o":"x")
		// .concat(wall_in_dir[3]==0?"o":"x")
		// .concat(wall_in_dir[4]==0?"o":"x")
		// .concat(wall_in_dir[5]==0?"o":"x")
		// .concat(wall_in_dir[6]==0?"o":"x")
		// .concat(wall_in_dir[7]==0?"o":"x");
		// dir = dir.concat(dir);
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

		index = dir.indexOf("ooooooo");
		if (index > -1)
			return Constants.directions[(index + 3) % 8];

		index = dir.indexOf("oooooo");
		if (index > -1)
			return Constants.directions[(index + 3) % 8];

		index = dir.indexOf("ooooo");
		if (index > -1)
			return Constants.directions[(index + 2) % 8];

		index = dir.indexOf("oooo");
		if (index > -1)
			return Constants.directions[(index + 2) % 8];

		index = dir.indexOf("ooo");
		if (index > -1)
			return Constants.directions[(index + 1) % 8];

		index = dir.indexOf("oo");
		if (index > -1)
			return Constants.directions[(index + 1) % 8];

		index = dir.indexOf("o");
		if (index > -1)
			return Constants.directions[(index) % 8];

		int lowest = closest_in_dir[0];
		int lowesti = 0;
		for (int x = 1; x < 8; x++)
			if (closest_in_dir[x] > lowest) {
				lowesti = x;
				lowest = closest_in_dir[x];
			}
		return Constants.directions[lowesti];
	}

	/**
	 * Gets the closest enemy Archon or Scorcher to the Scout. Prioritizes
	 * archons over scorchers. Returns null if none in range.
	 * 
	 * @return The RobotInfo of the closest Archon or Scorcher to the scout,
	 *         null if none in range.
	 */
	private RobotInfo getClosestEnemyArchonScorcher() {
		RobotInfo closestEnemyArchon = null;
		RobotInfo closestEnemyScorcher = null;
		int closestEnemyArchonDist = Integer.MAX_VALUE;
		int closestEnemyScorcherDist = Integer.MAX_VALUE;
		for (int idx = 0; idx < radar.numEnemyRobots; idx++) {
			RobotInfo enemy = radar.enemyInfos[radar.enemyRobots[idx]];
			// make sure non-null
			if (enemy == null) {
				continue;
			}
			// make sure it's an enemy
			if (enemy.team == myTeam) {
				continue;
			}
			// get distance
			int distance = curLoc.distanceSquaredTo(enemy.location);
			// check if it's an archon
			if (enemy.type == RobotType.ARCHON
					&& distance < closestEnemyArchonDist) {
				closestEnemyArchon = enemy;
				closestEnemyArchonDist = distance;
			}
			// check if it's a scorcher
			if (enemy.type == RobotType.SCORCHER
					&& distance < closestEnemyScorcherDist) {
				closestEnemyScorcher = enemy;
				closestEnemyScorcherDist = distance;
			}
		}
		// return the closest enemy Archon if there is one...
		if (closestEnemyArchon != null) {
			return closestEnemyArchon;

			// ... or the closest enemy Scorcher if there isn't any Archons...
		} else if (closestEnemyScorcher != null) {
			return closestEnemyScorcher;

			// ... or null if there are none of either
		} else {
			return null;
		}
	}

	/**
	 * @deprecated
	 */
	private MapLocation getBestRegenSquare() {
		long[] rows = new long[11];
		long[] ans = new long[11];
		// populate location of damaged allies
		// TODO(jven): consider only non-regened allies?
		for (int idx = 0; idx < radar.numAllyRobots; idx++) {
			RobotInfo ally = radar.allyInfos[radar.allyRobots[idx]];
			// ignore if he's not damaged
			if (ally.energon >= ally.type.maxEnergon || ally.regen) {
				continue;
			}
			// get relative location of ally
			int dx = ally.location.x - curLoc.x + 5;
			int dy = ally.location.y - curLoc.y + 5;
			// set initial bits
			if (ally.type == RobotType.ARCHON)
				rows[dy] |= (0x2L << (dx * 5));
			else
				rows[dy] |= (0x1L << (dx * 5));
		}
		// pre-shift lefts and rights
		for (int y = 0; y < 11; y++) {
			rows[y] += (rows[y] << 5) + (rows[y] >> 5) + (rows[y] << 10)
					+ (rows[y] >> 10);
		}

		// // sum consecutive 3 rows
		// for (int y = 1; y < 10; y++) {
		// ans[y] = rows[y - 1] + rows[y] + rows[y + 1];
		// }
		ans[1] = rows[0] + rows[1] + rows[2] + rows[3];
		ans[2] = ans[1] + rows[4];
		ans[3] = ans[2] + rows[5] - rows[0];
		ans[4] = ans[3] + rows[6] - rows[1];
		ans[5] = ans[4] + rows[7] - rows[2];
		ans[6] = ans[5] + rows[8] - rows[3];
		ans[7] = ans[6] + rows[9] - rows[4];
		ans[8] = ans[7] + rows[10] - rows[5];
		ans[9] = ans[8] - rows[6];

		// get max
		int maxX = 0;
		int maxY = 0;
		int maxDamagedAllies = 0;
		for (int xf = 5; xf < 50; xf += 5) {
			for (int y = 1; y < 10; y++) {
				int damagedAllies = (int) (((ans[y] >> (xf)) & 0x1fl));
				// if (damagedAllies > 2)
				if (damagedAllies > maxDamagedAllies) {
					maxX = xf;
					maxY = y;
					maxDamagedAllies = damagedAllies;
				}
			}
		}
		if (maxDamagedAllies > 0) {
			return curLoc.add(maxX / 5 - 5, maxY - 5);
		} else {
			return null;
		}
	}

	/**
	 * @deprecated
	 */
	private MapLocation getBestRegenSquareFast() {
		long r0, r1, r2, r3, r4, r5, r6, r7, r8, r9, r10;
		r0 = r1 = r2 = r3 = r4 = r5 = r6 = r7 = r8 = r9 = r10 = 0;
		long a1, a2, a3, a4, a5, a6, a7, a8, a9;
		a1 = a2 = a3 = a4 = a5 = a6 = a7 = a8 = a9 = 0;

		// populate location of damaged allies
		// TODO(jven): consider only non-regened allies? <- done
		for (int idx = 0; idx < radar.numAllyRobots; idx++) {
			RobotInfo ally = radar.allyInfos[radar.allyRobots[idx]];
			// ignore if he's not damaged
			if (ally.energon >= ally.type.maxEnergon || ally.regen) {
				continue;
			}
			// get relative location of ally
			int dx = ally.location.x - curLoc.x + 5;
			int dy = ally.location.y - curLoc.y + 5;
			// set initial bits
			if (ally.type == RobotType.ARCHON)
				switch (dy) {
				case 0:
					r0 |= (0x2L << (dx * 5));
					break;
				case 1:
					r1 |= (0x2L << (dx * 5));
					break;
				case 2:
					r2 |= (0x2L << (dx * 5));
					break;
				case 3:
					r3 |= (0x2L << (dx * 5));
					break;
				case 4:
					r4 |= (0x2L << (dx * 5));
					break;
				case 5:
					r5 |= (0x2L << (dx * 5));
					break;
				case 6:
					r6 |= (0x2L << (dx * 5));
					break;
				case 7:
					r7 |= (0x2L << (dx * 5));
					break;
				case 8:
					r8 |= (0x2L << (dx * 5));
					break;
				case 9:
					r9 |= (0x2L << (dx * 5));
					break;
				case 10:
					r10 |= (0x2L << (dx * 5));
					break;
				}
			else
				switch (dy) {
				case 0:
					r0 |= (0x1L << (dx * 5));
					break;
				case 1:
					r1 |= (0x1L << (dx * 5));
					break;
				case 2:
					r2 |= (0x1L << (dx * 5));
					break;
				case 3:
					r3 |= (0x1L << (dx * 5));
					break;
				case 4:
					r4 |= (0x1L << (dx * 5));
					break;
				case 5:
					r5 |= (0x1L << (dx * 5));
					break;
				case 6:
					r6 |= (0x1L << (dx * 5));
					break;
				case 7:
					r7 |= (0x1L << (dx * 5));
					break;
				case 8:
					r8 |= (0x1L << (dx * 5));
					break;
				case 9:
					r9 |= (0x1L << (dx * 5));
					break;
				case 10:
					r10 |= (0x1L << (dx * 5));
					break;
				}
		}

		r0 += (r0 << 10) + (r0 << 5) + (r0 >> 5) + (r0 >> 10);
		r1 += (r1 << 10) + (r1 << 5) + (r1 >> 5) + (r1 >> 10);
		r2 += (r2 << 10) + (r2 << 5) + (r2 >> 5) + (r2 >> 10);
		r3 += (r3 << 10) + (r3 << 5) + (r3 >> 5) + (r3 >> 10);
		r4 += (r4 << 10) + (r4 << 5) + (r4 >> 5) + (r4 >> 10);
		r5 += (r5 << 10) + (r5 << 5) + (r5 >> 5) + (r5 >> 10);
		r6 += (r6 << 10) + (r6 << 5) + (r6 >> 5) + (r6 >> 10);
		r7 += (r7 << 10) + (r7 << 5) + (r7 >> 5) + (r7 >> 10);
		r8 += (r8 << 10) + (r8 << 5) + (r8 >> 5) + (r8 >> 10);
		r9 += (r9 << 10) + (r9 << 5) + (r9 >> 5) + (r9 >> 10);
		r10 += (r10 << 10) + (r10 << 5) + (r10 >> 5) + (r10 >> 10);

		// pre-shift lefts and rights
		// for (int y = 0; y < 11; y++) {
		// rows[y] += (rows[y] << 5) + (rows[y] >> 5) + (rows[y] << 10) +
		// (rows[y] >> 10);
		// }
		//
		// // sum consecutive 3 rows
		// for (int y = 1; y < 10; y++) {
		// ans[y] = rows[y - 1] + rows[y] + rows[y + 1];
		// }

		a1 = r0 + r1 + r2 + r3;
		a2 = a1 + r4;
		a3 = a2 + r5 - r0;
		a4 = a3 + r6 - r1;
		a5 = a4 + r7 - r2;
		a6 = a5 + r8 - r3;
		a7 = a6 + r9 - r4;
		a8 = a7 + r10 - r5;
		a9 = a8 - r6;

		// ans[1] = rows[0] + rows[1] + rows[2] + rows[3];
		// ans[2] = ans[1] + rows[4];
		// ans[3] = ans[2] + rows[5] - rows[0];
		// ans[4] = ans[3] + rows[6] - rows[1];
		// ans[5] = ans[4] + rows[7] - rows[2];
		// ans[6] = ans[5] + rows[8] - rows[3];
		// ans[7] = ans[6] + rows[9] - rows[4];
		// ans[8] = ans[7] + rows[10] - rows[5];
		// ans[9] = ans[8] - rows[6];

		// get max
		int maxX = 0;
		int maxY = 0;
		int maxDamagedAllies = 0;
		int damagedAllies;

		// the below code is generated by:
		//
		// for (int y=-4; y<5; y++)
		// for (int x=-4; x<5; x++)
		// {
		// System.out.println("if ((damagedAllies=(int)((a"+(y+5)+">>"+((x+5)*5)+")&0x1fl))>maxDamagedAllies)");
		// System.out.println("{\n\tmaxDamagedAllies = damagedAllies;");
		// System.out.println("\tmaxX = "+x+";");
		// System.out.println("\tmaxY = "+y+";\n}");
		// }

		if ((damagedAllies = (int) ((a1 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a2 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a3 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a4 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a4 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a4 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a4 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a4 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a4 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a4 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a4 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a4 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a5 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a5 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a5 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a5 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a5 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a5 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a5 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a5 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a5 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a6 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a6 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a6 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a6 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a6 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a6 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a6 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a6 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a6 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a7 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a8 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a9 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = 4;
		}

		// for (int xf = 5; xf < 50; xf += 5) {
		// for (int y = 1; y < 10; y++) {
		// int damagedAllies = (int)(((ans[y] >> (xf)) & 0x1fl));
		// // if (damagedAllies > 2)
		// if (damagedAllies > maxDamagedAllies) {
		// maxX = xf;
		// maxY = y;
		// maxDamagedAllies = damagedAllies;
		// }
		// }
		// }

		if (maxDamagedAllies > 0) {
			return curLoc.add(maxX, maxY);
		} else {
			return null;
		}
	}

	/**
	 * The true best regen square function that we want
	 * 
	 * @return
	 */
	private MapLocation getBestRegenSquareFastFull() {
		long r0, r1, r2, r3, r4, r5, r6, r7, r8, r9, r10;
		r0 = r1 = r2 = r3 = r4 = r5 = r6 = r7 = r8 = r9 = r10 = 0x84210842108421L;
		long p0, p1, p2, p3, p4, p5, p6, p7, p8, p9, p10;
		p0 = p1 = p2 = p3 = p4 = p5 = p6 = p7 = p8 = p9 = p10 = 0;
		long a1, a2, a3, a4, a5, a6, a7, a8, a9;
		a1 = a2 = a3 = a4 = a5 = a6 = a7 = a8 = a9 = 0;

		radar.scan(true, false);

		int localCurLocX = curLoc.x;
		int localCurLocY = curLoc.y;

		// populate location of damaged allies
		for (int idx = radar.numAllyRobots; --idx >= 0;) {
			RobotInfo ally = radar.allyInfos[radar.allyRobots[idx]];

			// ignore if he's not damaged or he's out of flux
			if (ally.regen || ally.flux < 0.2
					|| ally.energon > (ally.type.maxEnergon - 0.2)) {
				continue;
			}

			// get relative location of ally
			int dx = ally.location.x - localCurLocX + 5;
			int dy = ally.location.y - localCurLocY + 5;

			// set initial bits
			switch (ally.type) {
			case ARCHON:
				switch (dy) {
				case 0:
					r0 += (0x2L << (dx * 5));
					break;
				case 1:
					r1 += (0x2L << (dx * 5));
					break;
				case 2:
					r2 += (0x2L << (dx * 5));
					break;
				case 3:
					r3 += (0x2L << (dx * 5));
					break;
				case 4:
					r4 += (0x2L << (dx * 5));
					break;
				case 5:
					r5 += (0x2L << (dx * 5));
					break;
				case 6:
					r6 += (0x2L << (dx * 5));
					break;
				case 7:
					r7 += (0x2L << (dx * 5));
					break;
				case 8:
					r8 += (0x2L << (dx * 5));
					break;
				case 9:
					r9 += (0x2L << (dx * 5));
					break;
				case 10:
					r10 += (0x2L << (dx * 5));
					break;
				}
				break;
			case SCOUT:
				switch (dy) {
				case 0:
					r0 -= (0x1L << (dx * 5));
					break;
				case 1:
					r1 -= (0x1L << (dx * 5));
					break;
				case 2:
					r2 -= (0x1L << (dx * 5));
					break;
				case 3:
					r3 -= (0x1L << (dx * 5));
					break;
				case 4:
					r4 -= (0x1L << (dx * 5));
					break;
				case 5:
					r5 -= (0x1L << (dx * 5));
					break;
				case 6:
					r6 -= (0x1L << (dx * 5));
					break;
				case 7:
					r7 -= (0x1L << (dx * 5));
					break;
				case 8:
					r8 -= (0x1L << (dx * 5));
					break;
				case 9:
					r9 -= (0x1L << (dx * 5));
					break;
				case 10:
					r10 -= (0x1L << (dx * 5));
					break;
				}
				break;
			default:
				switch (dy) {
				case 0:
					r0 += (0x1L << (dx * 5));
					break;
				case 1:
					r1 += (0x1L << (dx * 5));
					break;
				case 2:
					r2 += (0x1L << (dx * 5));
					break;
				case 3:
					r3 += (0x1L << (dx * 5));
					break;
				case 4:
					r4 += (0x1L << (dx * 5));
					break;
				case 5:
					r5 += (0x1L << (dx * 5));
					break;
				case 6:
					r6 += (0x1L << (dx * 5));
					break;
				case 7:
					r7 += (0x1L << (dx * 5));
					break;
				case 8:
					r8 += (0x1L << (dx * 5));
					break;
				case 9:
					r9 += (0x1L << (dx * 5));
					break;
				case 10:
					r10 += (0x1L << (dx * 5));
					break;
				}
				break;
			}

		}

		p0 = (r0 << 5) + r0 + (r0 >> 5);
		p1 = (r1 << 5) + r1 + (r1 >> 5);
		p2 = (r2 << 5) + r2 + (r2 >> 5);
		p3 = (r3 << 5) + r3 + (r3 >> 5);
		p4 = (r4 << 5) + r4 + (r4 >> 5);
		p5 = (r5 << 5) + r5 + (r5 >> 5);
		p6 = (r6 << 5) + r6 + (r6 >> 5);
		p7 = (r7 << 5) + r7 + (r7 >> 5);
		p8 = (r8 << 5) + r8 + (r8 >> 5);
		p9 = (r9 << 5) + r9 + (r9 >> 5);
		p10 = (r10 << 5) + r10 + (r10 >> 5);

		r0 = (r0 << 10) + p0 + (r0 >> 10);
		r1 = (r1 << 10) + p1 + (r1 >> 10);
		r2 = (r2 << 10) + p2 + (r2 >> 10);
		r3 = (r3 << 10) + p3 + (r3 >> 10);
		r4 = (r4 << 10) + p4 + (r4 >> 10);
		r5 = (r5 << 10) + p5 + (r5 >> 10);
		r6 = (r6 << 10) + p6 + (r6 >> 10);
		r7 = (r7 << 10) + p7 + (r7 >> 10);
		r8 = (r8 << 10) + p8 + (r8 >> 10);
		r9 = (r9 << 10) + p9 + (r9 >> 10);
		r10 = (r10 << 10) + p10 + (r10 >> 10);

		// pre-shift lefts and rights
		// for (int y = 0; y < 11; y++) {
		// rows[y] += (rows[y] << 5) + (rows[y] >> 5) + (rows[y] << 10) +
		// (rows[y] >> 10);
		// }
		//
		// // sum consecutive 3 rows
		// for (int y = 1; y < 10; y++) {
		// ans[y] = rows[y - 1] + rows[y] + rows[y + 1];
		// }

		a1 = r0 + r1 + r2 + p3;
		a2 = p0 + r1 + r2 + r3 + p4;
		a3 = p1 + r2 + r3 + r4 + p5;
		a4 = p2 + r3 + r4 + r5 + p6;
		a5 = p3 + r4 + r5 + r6 + p7;
		a6 = p4 + r5 + r6 + r7 + p8;
		a7 = p5 + r6 + r7 + r8 + p9;
		a8 = p6 + r7 + r8 + r9 + p10;
		a9 = p7 + r8 + r9 + r10;

		// a2 = a1+r4;
		// a3 = a2+r5-r0;
		// a4 = a3+r6-r1;
		// a5 = a4+r7-r2;
		// a6 = a5+r8-r3;
		// a7 = a6+r9-r4;
		// a8 = a7+r10-r5;
		// a9 = a8-r6;

		// ans[1] = rows[0] + rows[1] + rows[2] + rows[3];
		// ans[2] = ans[1] + rows[4];
		// ans[3] = ans[2] + rows[5] - rows[0];
		// ans[4] = ans[3] + rows[6] - rows[1];
		// ans[5] = ans[4] + rows[7] - rows[2];
		// ans[6] = ans[5] + rows[8] - rows[3];
		// ans[7] = ans[6] + rows[9] - rows[4];
		// ans[8] = ans[7] + rows[10] - rows[5];
		// ans[9] = ans[8] - rows[6];

		// get max
		int maxX = 0;
		int maxY = 0;
		int maxDamagedAllies = 0;
		int damagedAllies;

		// the below code is generated by:
		//
		// for (int[] xy : new int[][]
		// {{0,0},{-1,-1},{0,-1},{1,-1},{1,0},{1,1},{0,1},{-1,1},{-1,0},{-2,-2},{-1,-2},{0,-2},{1,-2},{2,-2},{2,-1},{2,0},{2,1},{2,2},{1,2},{0,2},{-1,2},{-2,2},{-2,1},{-2,0},{-2,-1},{-3,-3},{-2,-3},{-1,-3},{0,-3},{1,-3},{2,-3},{3,-3},{3,-2},{3,-1},{3,0},{3,1},{3,2},{3,3},{2,3},{1,3},{0,3},{-1,3},{-2,3},{-3,3},{-3,2},{-3,1},{-3,0},{-3,-1},{-3,-2},{-4,-4},{-3,-4},{-2,-4},{-1,-4},{0,-4},{1,-4},{2,-4},{3,-4},{4,-4},{4,-3},{4,-2},{4,-1},{4,0},{4,1},{4,2},{4,3},{4,4},{3,4},{2,4},{1,4},{0,4},{-1,4},{-2,4},{-3,4},{-4,4},{-4,3},{-4,2},{-4,1},{-4,0},{-4,-1},{-4,-2},{-4,-3}})
		// {
		// int x = xy[0];
		// int y = xy[1];
		// System.out.println("if ((damagedAllies=(int)((a"+(y+5)+">>"+((x+5)*5)+")&0x1fl))>maxDamagedAllies) {");
		// System.out.println("\tmaxDamagedAllies = damagedAllies;");
		// System.out.println("\tmaxX = "+x+";");
		// System.out.println("\tmaxY = "+y+";\n}");
		// }

		if ((damagedAllies = (int) ((a5 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a4 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a4 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a4 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a5 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a6 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a6 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a6 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a5 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a3 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a3 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a4 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a5 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a6 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a7 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a7 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a6 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a5 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a4 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a2 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a2 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a3 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a4 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a5 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a6 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a7 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a8 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a8 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a7 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a6 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a5 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a4 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a3 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a1 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a1 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = -4;
		}
		if ((damagedAllies = (int) ((a2 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = -3;
		}
		if ((damagedAllies = (int) ((a3 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a4 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a5 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a6 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a7 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a8 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a9 >> 45) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 4;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 40) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 3;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 35) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 2;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 30) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 1;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 25) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = 0;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 20) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -1;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 15) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -2;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 10) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -3;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a9 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = 4;
		}
		if ((damagedAllies = (int) ((a8 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = 3;
		}
		if ((damagedAllies = (int) ((a7 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = 2;
		}
		if ((damagedAllies = (int) ((a6 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = 1;
		}
		if ((damagedAllies = (int) ((a5 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = 0;
		}
		if ((damagedAllies = (int) ((a4 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = -1;
		}
		if ((damagedAllies = (int) ((a3 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = -2;
		}
		if ((damagedAllies = (int) ((a2 >> 5) & 0x1fl)) > maxDamagedAllies) {
			maxDamagedAllies = damagedAllies;
			maxX = -4;
			maxY = -3;
		}

		// for (int xf = 5; xf < 50; xf += 5) {
		// for (int y = 1; y < 10; y++) {
		// int damagedAllies = (int)(((ans[y] >> (xf)) & 0x1fl));
		// // if (damagedAllies > 2)
		// if (damagedAllies > maxDamagedAllies) {
		// maxX = xf;
		// maxY = y;
		// maxDamagedAllies = damagedAllies;
		// }
		// }
		// }

		if (maxDamagedAllies > 0) {
			return curLoc.add(maxX, maxY);
		} else {
			return null;
		}
	}
}
