package tcwolf;

import battlecode.common.Direction;
import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.RobotLevel;

public class SoldierRobot extends BaseRobot {
	private enum BehaviorState {
		LOOKING_TO_HIBERNATE, LOOKING_TO_LOW_FLUX_HIBERNATE, HIBERNATE, LOW_FLUX_HIBERNATE, POOL, SWARM, SEEK, LOST, REFUEL, ENEMY_DETECTED, LOOK_AROUND_FOR_ENEMIES,
	}

	int lockAcquiredRound;
	MapLocation target;
	MapLocation previousBugTarget;
	int closestSwarmTargetSenderDist;
	MapLocation archonSwarmTarget;
	boolean archonSwarmTargetIsMoving;
	int archonSwarmTime;
	BehaviorState behavior;
	MapLocation hibernateTarget;
	double energonLastTurn;
	double fluxLastTurn;
	MapLocation enemySpottedTarget;
	int enemySpottedRound;
	int roundLastWakenUp;
	int lastRoundTooClose;
	boolean checkedBehind;
	boolean movingTarget;

	public SoldierRobot(RobotController myRC) throws GameActionException {
		super(myRC);

		lockAcquiredRound = -1;
		closestSwarmTargetSenderDist = Integer.MAX_VALUE;
		nav.setNavigationMode(NavigationMode.BUG);
		io.setChannels(new BroadcastChannel[] { BroadcastChannel.ALL,
				BroadcastChannel.FIGHTERS, BroadcastChannel.EXTENDED_RADAR, });
		fbs.setPoolMode();
		behavior = BehaviorState.SWARM;
		enemySpottedTarget = null;
		enemySpottedRound = -55555;
		roundLastWakenUp = -55555;
		archonSwarmTime = -55555;
		lastRoundTooClose = -55555;
		energonLastTurn = 0;
		fluxLastTurn = 0;
		checkedBehind = false;
	}

	@Override
	public void run() throws GameActionException {
		boolean gotHitLastRound = curEnergon < energonLastTurn
				|| rc.getFlux() < fluxLastTurn - 1;
		if ((behavior == BehaviorState.SWARM
				|| behavior == BehaviorState.LOOKING_TO_HIBERNATE
				|| behavior == BehaviorState.LOST || behavior == BehaviorState.REFUEL)
				&& rc.isMovementActive() && !msm.justMoved()) {
			// No action if unnecessary

			energonLastTurn = curEnergon;
			fluxLastTurn = rc.getFlux();
			return;
		}

		// Scan everything
		radar.scan(true, true);

		int closestEnemyID = er.getClosestEnemyID();
		MapLocation closestEnemyLocation = closestEnemyID == -1 ? null
				: er.enemyLocationInfo[closestEnemyID];
		if (closestEnemyLocation != null
				&& rc.canSenseSquare(closestEnemyLocation))
			closestEnemyLocation = null;
		RobotInfo radarClosestEnemy = radar.closestEnemy;
		if (radarClosestEnemy != null
				&& (closestEnemyLocation == null || (radar.closestEnemyDist <= curLoc
						.distanceSquaredTo(closestEnemyLocation)))) {
			closestEnemyLocation = radarClosestEnemy.location;
		}
		boolean enemyNearby = closestEnemyLocation != null
				&& curLoc.distanceSquaredTo(closestEnemyLocation) <= 25;
		if (curRound % ExtendedRadarSystem.ALLY_MEMORY_TIMEOUT == myID
				% ExtendedRadarSystem.ALLY_MEMORY_TIMEOUT)
			radar.broadcastEnemyInfo(enemyNearby && curEnergon > 12);

		int distToClosestArchon = curLoc.distanceSquaredTo(dc
				.getClosestArchon());
		movingTarget = true;
		if (gotHitLastRound
				&& (closestEnemyLocation == null || curLoc
						.distanceSquaredTo(closestEnemyLocation) > 20)
				|| (behavior == BehaviorState.LOOK_AROUND_FOR_ENEMIES && !checkedBehind)) {
			// Got hurt since last turn.. look behind you
			behavior = BehaviorState.LOOK_AROUND_FOR_ENEMIES;
			checkedBehind = false;

		} else if (behavior == BehaviorState.LOOKING_TO_LOW_FLUX_HIBERNATE
				&& (closestEnemyLocation == null || curLoc
						.distanceSquaredTo(closestEnemyLocation) > 10)) {
			// Hibernate once we're on a non-blocking spot
			int adjacentMovable = 0;
			if (!rc.canMove(Direction.NORTH))
				adjacentMovable++;
			if (!rc.canMove(Direction.EAST))
				adjacentMovable++;
			if (!rc.canMove(Direction.WEST))
				adjacentMovable++;
			if (!rc.canMove(Direction.SOUTH))
				adjacentMovable++;
			boolean onPowerNode = rc.senseObjectAtLocation(curLoc,
					RobotLevel.POWER_NODE) != null;
			if (adjacentMovable <= 1 && !onPowerNode)
				behavior = BehaviorState.LOW_FLUX_HIBERNATE;

		} else if (closestEnemyLocation != null) {
			if (enemyNearby) {
				// If we know of an enemy, lock onto it
				behavior = BehaviorState.ENEMY_DETECTED;
				target = closestEnemyLocation;
				lockAcquiredRound = curRound;
			} else {
				// Look for enemy from the ER
				behavior = BehaviorState.SEEK;
				target = closestEnemyLocation;
			}

		} else if (behavior == BehaviorState.ENEMY_DETECTED
				&& curRound < lockAcquiredRound + 12) {
			// Don't know of any enemies, stay chasing the last enemy we knew of
			behavior = BehaviorState.ENEMY_DETECTED;

		} else if (curRound < enemySpottedRound
				+ Constants.ENEMY_SPOTTED_SIGNAL_TIMEOUT) {
			// Not even chasing anyone, try going to the enemy spotted signal
			behavior = BehaviorState.SEEK;
			target = enemySpottedTarget;
			movingTarget = false;

		} else if (behavior == BehaviorState.LOOKING_TO_HIBERNATE) {
			// Hibernate once we're on a non-blocking spot
			int adjacentMovable = 0;
			if (!rc.canMove(Direction.NORTH))
				adjacentMovable++;
			if (!rc.canMove(Direction.EAST))
				adjacentMovable++;
			if (!rc.canMove(Direction.WEST))
				adjacentMovable++;
			if (!rc.canMove(Direction.SOUTH))
				adjacentMovable++;
			boolean onPowerNode = rc.senseObjectAtLocation(curLoc,
					RobotLevel.POWER_NODE) != null;
			if (adjacentMovable <= 1 && !onPowerNode)
				behavior = BehaviorState.HIBERNATE;

		} else if (behavior == BehaviorState.LOST && distToClosestArchon > 32) {
			// If all allied archons are far away, move to closest one
			behavior = BehaviorState.LOST;
			target = dc.getClosestArchon();

		} else if (curRound <= archonSwarmTime + 12) {
			// Follow target of closest archon's broadcast
			behavior = BehaviorState.SWARM;
			target = archonSwarmTarget;
			movingTarget = archonSwarmTargetIsMoving;

			if (closestSwarmTargetSenderDist <= 36
					&& curLoc.distanceSquaredTo(target) <= 36
					&& curRound > roundLastWakenUp + 10) {
				// Close enough to swarm target, look for a place to hibernate
				behavior = BehaviorState.LOOKING_TO_HIBERNATE;
				hibernateTarget = target;
			}

		} else if (distToClosestArchon > 225) {
			// If all allied archons are very far away, look to hibernate
			behavior = BehaviorState.LOOKING_TO_HIBERNATE;
			target = curLoc;
			hibernateTarget = target;

		} else {
			// Received no swarm target broadcasts, enter lost mode
			behavior = BehaviorState.LOST;
			target = dc.getClosestArchon();

		}

		// Check if we need more flux
		if (behavior != BehaviorState.LOW_FLUX_HIBERNATE) {
			if (rc.getFlux() < 5) {
				if (closestEnemyLocation == null
						|| curLoc.distanceSquaredTo(closestEnemyLocation) > 10)
					behavior = BehaviorState.LOOKING_TO_LOW_FLUX_HIBERNATE;
			} else if (behavior == BehaviorState.SWARM
					|| behavior == BehaviorState.LOST
					|| behavior == BehaviorState.LOOKING_TO_HIBERNATE
					|| behavior == BehaviorState.SEEK) {
				if (rc.getFlux() < 10) {
					if (rc.getFlux() < Math.sqrt(curLoc.distanceSquaredTo(dc
							.getClosestArchon()))) {
						// Too low flux, can't reach archon
						if (curRound > roundLastWakenUp + 10)
							behavior = BehaviorState.LOOKING_TO_LOW_FLUX_HIBERNATE;
					} else {
						// Needs to get flux from archon
						behavior = BehaviorState.REFUEL;
						target = dc.getClosestArchon();
						movingTarget = true;
					}
				}
			}
		}

		// Attack an enemy if there is some unit in our attackable squares
		tryToAttack();

		// Check if we have too much flux
		if (behavior == BehaviorState.ENEMY_DETECTED
				|| behavior == BehaviorState.SEEK) {
			if (rc.getFlux() > myMaxEnergon * 2 / 3 + 15) {
				behavior = BehaviorState.POOL;
				target = dc.getClosestArchon();
				movingTarget = true;
			}
		}

		// Set nav target - if we have a moving target, don't change target
		// unless it's 20 dist away from previous target or the bug is not
		// tracing or we're adjacent to the old target
		if (!movingTarget || previousBugTarget == null || !nav.isBugTracing()
				|| target.distanceSquaredTo(previousBugTarget) > 20
				|| curLoc.distanceSquaredTo(previousBugTarget) <= 2) {
			nav.setDestination(target);
			previousBugTarget = target;
		}

		// Set the flux balance mode
		if (behavior == BehaviorState.SWARM)
			fbs.setBatteryMode();
		else
			fbs.setPoolMode();

		// Enter hibernation if desired
		if (behavior == BehaviorState.HIBERNATE
				|| behavior == BehaviorState.LOW_FLUX_HIBERNATE) {
			if (behavior == BehaviorState.HIBERNATE)
				hsys.setMode(HibernationSystem.MODE_NORMAL);
			else
				hsys.setMode(HibernationSystem.MODE_LOW_FLUX);

			msm.reset();
			er.reset();
			int ec = hsys.run();

			// Come out of hibernation
			if (ec == HibernationSystem.EXIT_ATTACKED) {
				radar.scan(false, true);
				if (radar.closestEnemy == null) {
					behavior = BehaviorState.LOOK_AROUND_FOR_ENEMIES;
					checkedBehind = false;
				} else {
					behavior = BehaviorState.ENEMY_DETECTED;
					tryToAttack();
				}
			} else if (ec == HibernationSystem.EXIT_MESSAGED) {
				behavior = BehaviorState.SWARM;
			} else if (ec == HibernationSystem.EXIT_REFUELED) {
				behavior = BehaviorState.SWARM;
			}
			roundLastWakenUp = curRound;
			target = (behavior == BehaviorState.ENEMY_DETECTED) ? radar.closestEnemy.location
					: curLoc;
			nav.setDestination(target);

		}

		// Update end of turn variables
		energonLastTurn = curEnergon;
		fluxLastTurn = rc.getFlux();
	}

	private void tryToAttack() throws GameActionException {
		if (!rc.isAttackActive()) {
			RobotInfo bestInfo = null;
			double bestValue = Double.MAX_VALUE;
			for (int n = 0; n < radar.numEnemyRobots; n++) {
				RobotInfo ri = radar.enemyInfos[radar.enemyRobots[n]];
				if (!rc.canAttackSquare(ri.location))
					continue;
				if ((bestValue <= myType.attackPower && ri.energon <= myType.attackPower) ? ri.energon > bestValue
						: ri.energon < bestValue) {
					// Say a soldier does 6 damage. We prefer hitting units with
					// less energon, but we also would rather hit a unit with 5
					// energon than a unit with 1 energon.
					bestInfo = ri;
					bestValue = ri.energon;
				}
			}

			if (bestInfo != null) {
				if (bestValue <= myType.attackPower) {
					er.broadcastKill(bestInfo.robot.getID());
				}
				rc.attackSquare(bestInfo.location, bestInfo.type.level);
			}
		}
	}

	@Override
	public void processMessage(BroadcastType msgType, StringBuilder sb)
			throws GameActionException {
		int[] shorts;
		switch (msgType) {
		case ENEMY_SPOTTED:
			shorts = BroadcastSystem.decodeUShorts(sb);
			if (shorts[0] > enemySpottedRound) {
				enemySpottedRound = shorts[0];
				enemySpottedTarget = new MapLocation(shorts[1], shorts[2]);
			}
			break;
		case SWARM_TARGET:
			shorts = BroadcastSystem.decodeUShorts(sb);
			int dist = curLoc.distanceSquaredTo(BroadcastSystem
					.decodeSenderLoc(sb));
			if (dist < closestSwarmTargetSenderDist
					|| curRound > archonSwarmTime + 5) {
				closestSwarmTargetSenderDist = dist;
				archonSwarmTargetIsMoving = shorts[0] != 0;
				archonSwarmTarget = new MapLocation(shorts[1], shorts[2]);
				archonSwarmTime = curRound;
			}
			break;
		case ENEMY_INFO:
			er.integrateEnemyInfo(BroadcastSystem.decodeUShorts(sb));
			break;
		case ENEMY_KILL:
			er.integrateEnemyKill(BroadcastSystem.decodeShort(sb));
			break;
		case MAP_EDGES:
			ses.receiveMapEdges(BroadcastSystem.decodeUShorts(sb));
			break;
		default:
			super.processMessage(msgType, sb);
		}
	}

	@Override
	public MoveInfo computeNextMove() throws GameActionException {
		if (rc.getFlux() < 0.7)
			return new MoveInfo(curDir.opposite());

		if (behavior == BehaviorState.LOOK_AROUND_FOR_ENEMIES) {
			// Just turn around once
			checkedBehind = true;
			return new MoveInfo(curDir.opposite());

		} else if (behavior == BehaviorState.LOOKING_TO_HIBERNATE) {
			// If we're looking to hibernate, move around randomly
			if (Util.randDouble() < 0.2)
				return new MoveInfo(curLoc.directionTo(target).opposite(),
						false);
			else
				return new MoveInfo(nav.navigateCompletelyRandomly(), false);

		} else if (behavior == BehaviorState.LOOKING_TO_LOW_FLUX_HIBERNATE) {
			// If we're looking to low flux hibernate, move around randomly
			return new MoveInfo(nav.navigateCompletelyRandomly(), false);

		} else if (behavior == BehaviorState.SWARM) {
			// If we're on top of our target power node, move around randomly
			if (curLoc.equals(target)
					&& rc.senseObjectAtLocation(curLoc, RobotLevel.POWER_NODE) != null) {
				return new MoveInfo(nav.navigateCompletelyRandomly(), false);
			}
			// If we're far from the swarm target, follow normal swarm rules
			if (curLoc.distanceSquaredTo(target) >= 11) {
				Direction dir = nav.navigateToDestination();
				if (dir == null)
					return null;
				if (behavior == BehaviorState.SWARM && !nav.isBugTracing()) {
					if (radar.alliesInFront == 0 && Util.randDouble() < 0.6)
						return null;
					if (radar.alliesInFront > 3
							&& Util.randDouble() < 0.05 * radar.alliesInFront)
						dir = nav.navigateCompletelyRandomly();
					if (radar.alliesOnLeft > radar.alliesOnRight
							&& Util.randDouble() < 0.4)
						dir = dir.rotateRight();
					else if (radar.alliesOnLeft < radar.alliesOnRight
							&& Util.randDouble() < 0.4)
						dir = dir.rotateLeft();
				}
				return new MoveInfo(dir, false);

				// If we're fairly close, and there's lots of allies around,
				// move randomly
			} else if (curLoc.distanceSquaredTo(target) >= 2) {
				if (radar.alliesInFront > 3
						&& Util.randDouble() < 0.05 * radar.alliesInFront)
					return new MoveInfo(nav.navigateCompletelyRandomly(), false);
			}

		} else if (behavior == BehaviorState.ENEMY_DETECTED) {
			// Fighting an enemy, kite target
			MapLocation midpoint = new MapLocation((curLoc.x + target.x) / 2,
					(curLoc.y + target.y) / 2);
			int strengthDifference = er.getStrengthDifference(midpoint, 24);
			boolean weHaveBiggerFront = strengthDifference > 6;
			boolean targetIsRanged = radar.numEnemyDisruptors
					+ radar.numEnemyScorchers > 0;
			int tooClose = weHaveBiggerFront ? -1 : (targetIsRanged ? 10 : 5);
			int tooFar = weHaveBiggerFront ? 4 : (targetIsRanged ? 26 : 26);
			int distToTarget = curLoc.distanceSquaredTo(target);
			Direction dirToTarget = curLoc.directionTo(target);

			if (distToTarget <= 13
					&& (curDir.ordinal() - dirToTarget.ordinal() + 9) % 8 > 2) {
				return new MoveInfo(dirToTarget);

				// If we are too close to the target, back off
			} else if (distToTarget <= tooClose) {
				if (radar.numEnemyScorchers == radar.numEnemyRobots
						&& radar.numEnemyScorchers > 0) {
					if (distToTarget <= 5) {
						if (rc.canMove(dirToTarget))
							return new MoveInfo(dirToTarget, false);
						else
							return new MoveInfo(dirToTarget);
					}
				}
				lastRoundTooClose = curRound;
				if (rc.canMove(curDir.opposite()))
					return new MoveInfo(curDir.opposite(), true);
				Direction opp = curDir.opposite();
				Direction dir = opp.rotateLeft();
				if (isOptimalRetreatingDirection(dir, target)
						&& rc.canMove(dir))
					return new MoveInfo(dir, true);
				dir = opp.rotateRight();
				if (isOptimalRetreatingDirection(dir, target)
						&& rc.canMove(dir))
					return new MoveInfo(dir, true);
				dir = opp.rotateLeft().rotateLeft();
				if (isOptimalRetreatingDirection(dir, target)
						&& rc.canMove(dir))
					return new MoveInfo(dir, true);
				dir = opp.rotateRight().rotateRight();
				if (isOptimalRetreatingDirection(dir, target)
						&& rc.canMove(dir))
					return new MoveInfo(dir, true);
				if (targetIsRanged)
					return new MoveInfo(dirToTarget.opposite(), true);

			} else if (curEnergon <= 12) {
				return new MoveInfo(curLoc.directionTo(dc.getClosestArchon()),
						true);

				// If we are too far from the target, advance
			} else if (distToTarget >= tooFar) {
				if (curRound < lastRoundTooClose + 12)
					return new MoveInfo(curLoc.directionTo(target));
				if (distToTarget <= 5) {
					if (rc.canMove(dirToTarget))
						return new MoveInfo(dirToTarget, false);
					else if (rc.canMove(dirToTarget.rotateLeft())
							&& isOptimalAdvancingDirection(
									dirToTarget.rotateLeft(), target,
									dirToTarget))
						return new MoveInfo(dirToTarget.rotateLeft(), false);
					else if (rc.canMove(dirToTarget.rotateRight())
							&& isOptimalAdvancingDirection(
									dirToTarget.rotateRight(), target,
									dirToTarget))
						return new MoveInfo(dirToTarget.rotateRight(), false);
					else
						return new MoveInfo(dirToTarget);
				} else if (distToTarget >= 26) {
					return new MoveInfo(nav.navigateToDestination(), false);
				} else {
					return new MoveInfo(dirToTarget, false);
				}
			}

		} else if (curLoc.distanceSquaredTo(target) > 2) {
			// Go towards target
			return new MoveInfo(nav.navigateToDestination(), false);
		}

		// Default action is turning towards target
		return new MoveInfo(curLoc.directionTo(target));
	}

	private boolean isOptimalAdvancingDirection(Direction dir,
			MapLocation target, Direction dirToTarget) {
		int dx = target.x - curLoc.x;
		int dy = target.y - curLoc.y;
		switch (dx) {
		case -2:
			if (dy == 1)
				return dir == Direction.WEST || dir == Direction.SOUTH_WEST;
			else if (dy == -1)
				return dir == Direction.WEST || dir == Direction.NORTH_WEST;
			break;
		case -1:
			if (dy == 2)
				return dir == Direction.SOUTH || dir == Direction.SOUTH_WEST;
			else if (dy == -2)
				return dir == Direction.NORTH || dir == Direction.NORTH_WEST;
			break;
		case 1:
			if (dy == 2)
				return dir == Direction.SOUTH || dir == Direction.SOUTH_EAST;
			else if (dy == -2)
				return dir == Direction.NORTH || dir == Direction.NORTH_EAST;
			break;
		case 2:
			if (dy == 1)
				return dir == Direction.EAST || dir == Direction.SOUTH_EAST;
			else if (dy == -1)
				return dir == Direction.EAST || dir == Direction.NORTH_EAST;
			break;
		default:
			break;
		}
		return dir == dirToTarget;
	}

	private boolean isOptimalRetreatingDirection(Direction dir,
			MapLocation target) {
		int dx = curLoc.x - target.x;
		int dy = curLoc.y - target.y;
		if (dx == 0) {
			if (dy == 0)
				return true;
			if (dy > 0)
				return dir == Direction.SOUTH || dir == Direction.SOUTH_EAST
						|| dir == Direction.SOUTH_WEST;
			return dir == Direction.NORTH || dir == Direction.NORTH_EAST
					|| dir == Direction.NORTH_WEST;
		}
		if (dx > 0) {
			if (dy > dx)
				return dir == Direction.SOUTH_EAST || dir == Direction.SOUTH;
			if (dy == dx)
				return dir == Direction.SOUTH_EAST || dir == Direction.SOUTH
						|| dir == Direction.EAST;
			if (dy > 0)
				return dir == Direction.EAST || dir == Direction.SOUTH_EAST;
			if (dy == 0)
				return dir == Direction.EAST || dir == Direction.NORTH_EAST
						|| dir == Direction.SOUTH_EAST;
			if (dy > -dx)
				return dir == Direction.EAST || dir == Direction.NORTH_EAST;
			if (dy == -dx)
				return dir == Direction.EAST || dir == Direction.NORTH_EAST
						|| dir == Direction.NORTH;
			return dir == Direction.NORTH || dir == Direction.NORTH_EAST;
		}
		dx = -dx;
		if (dy > dx)
			return dir == Direction.SOUTH_WEST || dir == Direction.SOUTH;
		if (dy == dx)
			return dir == Direction.SOUTH_WEST || dir == Direction.SOUTH
					|| dir == Direction.WEST;
		if (dy > 0)
			return dir == Direction.WEST || dir == Direction.SOUTH_WEST;
		if (dy == 0)
			return dir == Direction.WEST || dir == Direction.NORTH_WEST
					|| dir == Direction.SOUTH_WEST;
		if (dy > -dx)
			return dir == Direction.WEST || dir == Direction.NORTH_WEST;
		if (dy == -dx)
			return dir == Direction.WEST || dir == Direction.NORTH_WEST
					|| dir == Direction.NORTH;
		return dir == Direction.NORTH || dir == Direction.NORTH_WEST;
	}

	@Override
	public void useExtraBytecodes() throws GameActionException {
		super.useExtraBytecodes();
		fluxLastTurn = rc.getFlux();
	}
}
