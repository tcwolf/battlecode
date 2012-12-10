package tcwolf;

import battlecode.common.Direction;
import battlecode.common.MapLocation;
import battlecode.common.PowerNode;
import battlecode.common.RobotType;
import battlecode.common.TerrainTile;

//The data structure is used to store terrain of the world map as sensed by one robot.
//  It stores a 256x256 boolean array representing the tiles that are walls.
//  Static variables should definitely be changed if any map constants are modified

public class MapCacheSystem {
	public final static int MAP_SIZE = 256;
	public final static int POWER_CORE_POSITION = 128;
	public final static int MAP_BLOCK_SIZE = 4;
	public final static int PACKED_MAP_SIZE = 64;

	final BaseRobot br;
	final boolean[][] isWall;
	final int[][] packedIsWall;
	final boolean[][] sensed;
	final int[][] packedSensed;
	final FastUShortSet packedDataUpdated;
	final short[][] powerNodeID;
	final PowerNodeGraph powerNodeGraph;
	final int powerCoreWorldX, powerCoreWorldY;
	public int edgeXMin, edgeXMax, edgeYMin, edgeYMax;
	int senseRadius;
	private final int[][][] optimizedSensingList;

	public MapCacheSystem(BaseRobot baseRobot) {
		this.br = baseRobot;
		isWall = new boolean[MAP_SIZE][MAP_SIZE];
		sensed = new boolean[MAP_SIZE][MAP_SIZE];
		packedIsWall = new int[PACKED_MAP_SIZE][PACKED_MAP_SIZE];
		packedSensed = new int[PACKED_MAP_SIZE][PACKED_MAP_SIZE];
		packedDataUpdated = new FastUShortSet();
		initPackedDataStructures();
		powerNodeID = new short[MAP_SIZE][MAP_SIZE];
		MapLocation loc = baseRobot.rc.sensePowerCore().getLocation();
		powerCoreWorldX = loc.x;
		powerCoreWorldY = loc.y;
		powerNodeGraph = new PowerNodeGraph();
		edgeXMin = 0;
		edgeXMax = 0;
		edgeYMin = 0;
		edgeYMax = 0;
		senseRadius = (int) Math.sqrt(baseRobot.myType.sensorRadiusSquared);
		switch (baseRobot.myType) {
		case ARCHON:
			optimizedSensingList = sensorRangeARCHON;
			break;
		case SCOUT:
			optimizedSensingList = sensorRangeSCOUT;
			break;
		case DISRUPTER:
			optimizedSensingList = sensorRangeDISRUPTER;
			break;
		case SCORCHER:
			optimizedSensingList = sensorRangeSCORCHER;
			break;
		case SOLDIER:
			optimizedSensingList = sensorRangeSOLDIER;
			break;
		default:
			optimizedSensingList = new int[0][0][0];
		}
	}

	private void initPackedDataStructures() {
		// 17,47 are optimized magic numbers from (128-60)/4 and (128+60)/4
		for (int xb = 17; xb < 47; xb++)
			for (int yb = 17; yb < 47; yb++) {
				packedIsWall[xb][yb] = xb * (1 << 22) + yb * (1 << 16);
			}
		for (int xb = 17; xb < 47; xb++)
			System.arraycopy(packedIsWall[xb], 17, packedSensed[xb], 17, 30);
	}

	@Override
	public String toString() {
		StringBuilder sb = new StringBuilder("\nSurrounding map data:\n");
		int myX = worldToCacheX(br.curLoc.x);
		int myY = worldToCacheY(br.curLoc.y);
		for (int y = myY - 10; y < myY + 10; y++) {
			for (int x = myX - 10; x < myX + 10; x++)
				sb.append((y == myY && x == myX) ? 'x' : (!sensed[x][y]) ? 'o'
						: (isWall[x][y]) ? '#' : '.');
			sb.append("\n");
		}
		sb.append("Edge data: \nx=[" + edgeXMin + "," + edgeXMax + "] y=["
				+ edgeYMin + "," + edgeYMax + "] \n");
		sb.append("Power node graph:");
		sb.append(powerNodeGraph.toString());
		return sb.toString();
	}

	// Returns secret location of power nodes, if robot knows
	public MapLocation getEnemyPowerCoreLocation() {
		return powerNodeGraph.nodeLocations[powerNodeGraph.enemyPowerCoreID];
	}

	// Returns the secret power core location, or, guesses if it doesn't know
	public MapLocation guessEnemyPowerCoreLocation() {
		// Return if we know for sure where the enemy core is
		MapLocation knownEnemyCoreLoc = getEnemyPowerCoreLocation();
		if (knownEnemyCoreLoc != null)
			return knownEnemyCoreLoc;

		// If no map edges known, add up vectors of our base to each known
		// power node location, and return a location far in that direction
		int mapEdgesKnown = 0;
		if (edgeXMin != 0)
			mapEdgesKnown++;
		if (edgeXMax != 0)
			mapEdgesKnown++;
		if (edgeYMin != 0)
			mapEdgesKnown++;
		if (edgeYMax != 0)
			mapEdgesKnown++;
		if (mapEdgesKnown == 0) {
			int sdx = 0;
			int sdy = 0;
			for (int i = 2; i < powerNodeGraph.nodeCount; i++) {
				sdx += powerNodeGraph.nodeLocations[i].x - powerCoreWorldX;
				sdy += powerNodeGraph.nodeLocations[i].y - powerCoreWorldY;
			}
			double magnitude = Math.sqrt(sdx * sdx + sdy * sdy);
			sdx = (int) (sdx * 90 / magnitude);
			sdy = (int) (sdx * 90 / magnitude);
			return new MapLocation(powerCoreWorldX + sdx, powerCoreWorldY + sdy);
		}

		// Current heuristic: - assume map size is 60 if we don't know it. -
		// assume rotational symmetry
		int mapSize = 61;
		int xminGuess = edgeXMin;
		int xmaxGuess = edgeXMax;
		int yminGuess = edgeYMin;
		int ymaxGuess = edgeYMax;
		if (xminGuess == 0) {
			if (xmaxGuess == 0) {
				xminGuess = POWER_CORE_POSITION;
				xmaxGuess = POWER_CORE_POSITION;
			} else {
				xminGuess = xmaxGuess - mapSize;
			}
		} else if (xmaxGuess == 0) {
			xmaxGuess = xminGuess + mapSize;
		}
		if (yminGuess == 0) {
			if (ymaxGuess == 0) {
				yminGuess = POWER_CORE_POSITION;
				ymaxGuess = POWER_CORE_POSITION;
			} else {
				yminGuess = ymaxGuess - mapSize;
			}
		} else if (ymaxGuess == 0) {
			ymaxGuess = yminGuess + mapSize;
		}
		int x = xminGuess + xmaxGuess - POWER_CORE_POSITION;
		int y = yminGuess + ymaxGuess - POWER_CORE_POSITION;
		MapLocation guess = new MapLocation(cacheToWorldX(x), cacheToWorldY(y));
		if (!isSensed(guess))
			return guess;
		x = xminGuess + xmaxGuess - POWER_CORE_POSITION;
		y = POWER_CORE_POSITION;
		guess = new MapLocation(cacheToWorldX(x), cacheToWorldY(y));
		if (!isSensed(guess))
			return guess;
		x = POWER_CORE_POSITION;
		y = yminGuess + ymaxGuess - POWER_CORE_POSITION;
		guess = new MapLocation(cacheToWorldX(x), cacheToWorldY(y));
		if (!isSensed(guess))
			return guess;
		x = xminGuess + ymaxGuess - POWER_CORE_POSITION;
		y = yminGuess + xmaxGuess - POWER_CORE_POSITION;
		guess = new MapLocation(cacheToWorldX(x), cacheToWorldY(y));
		if (!isSensed(guess))
			return guess;
		x = xminGuess - yminGuess + POWER_CORE_POSITION;
		y = yminGuess - xminGuess + POWER_CORE_POSITION;
		guess = new MapLocation(cacheToWorldX(x), cacheToWorldY(y));
		if (!isSensed(guess))
			return guess;
		if (edgeXMin == 0)
			return br.curLoc.add(Direction.WEST, 60);
		if (edgeXMax == 0)
			return br.curLoc.add(Direction.EAST, 60);
		if (edgeYMin == 0)
			return br.curLoc.add(Direction.NORTH, 60);
		if (edgeYMax == 0)
			return br.curLoc.add(Direction.SOUTH, 60);
		return br.curLoc.add(NavigationSystem.getRandomDirection(), 60);
	}

	public MapLocation guessBestPowerNodeToCapture() {
		MapLocation enemyPowerCoreGuess = guessEnemyPowerCoreLocation();
		MapLocation[] nodeLocs = br.dc.getCapturablePowerCores();
		MapLocation bestLoc = null;
		double bestValue = Integer.MAX_VALUE;
		for (MapLocation loc : nodeLocs) {
			double value = Math.sqrt(br.curLoc.distanceSquaredTo(loc))
					+ Math.sqrt(loc.distanceSquaredTo(enemyPowerCoreGuess));
			if (value < bestValue) {
				bestValue = value;
				bestLoc = loc;
			}
		}
		return bestLoc;
	}

	public MapLocation getEndGamePowerNodeToCapture() {
		MapLocation[] nodeLocs = br.dc.getCapturablePowerCores();
		double rand = Util.randDouble();
		if (br.myArchonID == 0 || (br.myArchonID >= 3 && rand < 0.25)) {
			int randomIndex = (int) (Util.randDouble() * nodeLocs.length);
			return nodeLocs[randomIndex];
		} else if (br.myArchonID == 1 || (br.myArchonID >= 3 && rand < 0.5)) {
			// Take power node farthest from enemy base
			MapLocation enemyCore = guessEnemyPowerCoreLocation();
			int farthestIndex = -1;
			int farthestDist = -1;
			for (int i = 0; i < nodeLocs.length; i++) {
				int dist = enemyCore.distanceSquaredTo(nodeLocs[i]);
				if (dist > farthestDist) {
					farthestIndex = i;
					farthestDist = dist;
				}
			}
			return nodeLocs[farthestIndex];
		} else {
			int closestIndex = -1;
			int closestDist = Integer.MAX_VALUE;
			for (int i = 0; i < nodeLocs.length; i++) {
				int dist = br.curLoc.distanceSquaredTo(nodeLocs[i]);
				if (dist < closestDist) {
					closestIndex = i;
					closestDist = dist;
				}
			}
			return nodeLocs[closestIndex];
		}
	}

	public void senseAll() {
		if (br.myType != RobotType.ARCHON && br.myType != RobotType.SCOUT)
			return;
		senseAllTiles();
		senseAllMapEdges();
		sensePowerNodes();
	}

	public void senseAfterMove(Direction lastMoved) {
		if (br.myType != RobotType.ARCHON && br.myType != RobotType.SCOUT)
			return;
		if (lastMoved == null || lastMoved == Direction.NONE
				|| lastMoved == Direction.OMNI) {
			return;
		}
		senseTilesOptimized(lastMoved);
		senseMapEdgesOptimized(lastMoved);
		sensePowerNodes();
	}

	private void senseAllTiles() {
		MapLocation myLoc = br.curLoc;
		int myX = worldToCacheX(myLoc.x);
		int myY = worldToCacheY(myLoc.y);
		for (int dx = -senseRadius; dx <= senseRadius; dx++)
			for (int dy = -senseRadius; dy <= senseRadius; dy++) {
				int x = myX + dx;
				int y = myY + dy;
				int xblock = x / MAP_BLOCK_SIZE;
				int yblock = y / MAP_BLOCK_SIZE;
				if (sensed[x][y])
					continue;
				MapLocation loc = myLoc.add(dx, dy);
				TerrainTile tt = br.rc.senseTerrainTile(loc);
				if (tt != null) {
					boolean b = (tt != TerrainTile.LAND);
					isWall[x][y] = b;
					if (b)
						packedIsWall[xblock][yblock] |= (1 << (x % 4 * 4 + y % 4));
					sensed[x][y] = true;
					packedSensed[xblock][yblock] |= (1 << (x % 4 * 4 + y % 4));
				}
			}
	}

	private void senseTilesOptimized(Direction lastMoved) {
		final int[][] list = optimizedSensingList[lastMoved.ordinal()];
		MapLocation myLoc = br.curLoc;
		int myX = worldToCacheX(myLoc.x);
		int myY = worldToCacheY(myLoc.y);
		TangentBug tb = br.nav.tangentBug;
		for (int i = 0; i < list.length; i++) {
			int dx = list[i][0];
			int dy = list[i][1];
			int x = myX + dx;
			int y = myY + dy;
			int xblock = x / MAP_BLOCK_SIZE;
			int yblock = y / MAP_BLOCK_SIZE;
			if (sensed[x][y])
				continue;
			MapLocation loc = myLoc.add(dx, dy);
			TerrainTile tt = br.rc.senseTerrainTile(loc);
			if (tt != null) {
				boolean b = (tt != TerrainTile.LAND);
				isWall[x][y] = b;
				if (b) {
					packedIsWall[xblock][yblock] |= (1 << (x % 4 * 4 + y % 4));
					if (tb.wallCache[x][y] > tb.curWallCacheID
							* TangentBug.BUFFER_LENGTH)
						tb.reset();
				}
				sensed[x][y] = true;
				packedSensed[xblock][yblock] |= (1 << (x % 4 * 4 + y % 4));
			}
		}
	}

	private void insertArtificialWall(int cacheX, int cacheY) {
		isWall[cacheX][cacheY] = true;
		sensed[cacheX][cacheY] = true;
		packedIsWall[cacheX / 4][cacheY / 4] |= (1 << (cacheX % 4 * 4 + cacheY % 4));
		packedSensed[cacheX / 4][cacheY / 4] |= (1 << (cacheX % 4 * 4 + cacheY % 4));
	}

	/** Combines packed terrain data with existing packed terrain data. */
	public void integrateTerrainInfo(int packedIsWallInfo, int packedSensedInfo) {
		int block = (packedIsWallInfo >> 16);
		int xblock = block / 64;
		int yblock = block % 64;
		if (packedSensed[xblock][yblock] != packedSensedInfo) {
			packedDataUpdated.add(block);
			packedIsWall[xblock][yblock] |= packedIsWallInfo;
			packedSensed[xblock][yblock] |= packedSensedInfo;
		}
	}

	public boolean extractUpdatedPackedDataStep() {
		if (packedDataUpdated.isEmpty())
			return true;
		int block = packedDataUpdated.pop();
		int xblock = block / 64;
		int yblock = block % 64;
		int isWallData = packedIsWall[xblock][yblock];
		int sensedData = packedSensed[xblock][yblock];
		for (int bit = 0; bit < 16; bit++) {
			int x = xblock * MAP_BLOCK_SIZE + bit / 4;
			int y = yblock * MAP_BLOCK_SIZE + bit % 4;
			isWall[x][y] = ((isWallData & (1 << bit)) != 0);
			sensed[x][y] = ((sensedData & (1 << bit)) != 0);
		}
		return false;
	}

	private void senseAllMapEdges() {
		MapLocation myLoc = br.curLoc;
		if (edgeXMin == 0
				&& br.rc.senseTerrainTile(myLoc
						.add(Direction.WEST, senseRadius)) == TerrainTile.OFF_MAP) {
			int d = senseRadius;
			while (br.rc.senseTerrainTile(myLoc.add(Direction.WEST, d - 1)) == TerrainTile.OFF_MAP) {
				d--;
			}
			edgeXMin = worldToCacheX(myLoc.x) - d;
		}
		if (edgeXMax == 0
				&& br.rc.senseTerrainTile(myLoc
						.add(Direction.EAST, senseRadius)) == TerrainTile.OFF_MAP) {
			int d = senseRadius;
			while (br.rc.senseTerrainTile(myLoc.add(Direction.EAST, d - 1)) == TerrainTile.OFF_MAP) {
				d--;
			}
			edgeXMax = worldToCacheX(myLoc.x) + d;
		}
		if (edgeYMin == 0
				&& br.rc.senseTerrainTile(myLoc.add(Direction.NORTH,
						senseRadius)) == TerrainTile.OFF_MAP) {
			int d = senseRadius;
			while (br.rc.senseTerrainTile(myLoc.add(Direction.NORTH, d - 1)) == TerrainTile.OFF_MAP) {
				d--;
			}
			edgeYMin = worldToCacheY(myLoc.y) - d;
		}
		if (edgeYMax == 0
				&& br.rc.senseTerrainTile(myLoc.add(Direction.SOUTH,
						senseRadius)) == TerrainTile.OFF_MAP) {
			int d = senseRadius;
			while (br.rc.senseTerrainTile(myLoc.add(Direction.SOUTH, d - 1)) == TerrainTile.OFF_MAP) {
				d--;
			}
			edgeYMax = worldToCacheY(myLoc.y) + d;
		}
	}

	private void senseMapEdgesOptimized(Direction lastMoved) {
		MapLocation myLoc = br.curLoc;
		if (edgeXMin == 0
				&& lastMoved.dx == -1
				&& br.rc.senseTerrainTile(myLoc
						.add(Direction.WEST, senseRadius)) == TerrainTile.OFF_MAP) {
			int d = senseRadius;
			// Note that some of this code is not necessary if we use the system
			// properly
			// But it adds a little bit of robustness
			while (br.rc.senseTerrainTile(myLoc.add(Direction.WEST, d - 1)) == TerrainTile.OFF_MAP) {
				d--;
			}
			edgeXMin = worldToCacheX(myLoc.x) - d;
		}
		if (edgeXMax == 0
				&& lastMoved.dx == 1
				&& br.rc.senseTerrainTile(myLoc
						.add(Direction.EAST, senseRadius)) == TerrainTile.OFF_MAP) {
			int d = senseRadius;
			while (br.rc.senseTerrainTile(myLoc.add(Direction.EAST, d - 1)) == TerrainTile.OFF_MAP) {
				d--;
			}
			edgeXMax = worldToCacheX(myLoc.x) + d;
		}
		if (edgeYMin == 0
				&& lastMoved.dy == -1
				&& br.rc.senseTerrainTile(myLoc.add(Direction.NORTH,
						senseRadius)) == TerrainTile.OFF_MAP) {
			int d = senseRadius;
			while (br.rc.senseTerrainTile(myLoc.add(Direction.NORTH, d - 1)) == TerrainTile.OFF_MAP) {
				d--;
			}
			edgeYMin = worldToCacheY(myLoc.y) - d;
		}
		if (edgeYMax == 0
				&& lastMoved.dy == 1
				&& br.rc.senseTerrainTile(myLoc.add(Direction.SOUTH,
						senseRadius)) == TerrainTile.OFF_MAP) {
			int d = senseRadius;
			while (br.rc.senseTerrainTile(myLoc.add(Direction.SOUTH, d - 1)) == TerrainTile.OFF_MAP) {
				d--;
			}
			edgeYMax = worldToCacheY(myLoc.y) + d;
		}
	}

	private void sensePowerNodes() {
		for (PowerNode node : br.rc.senseNearbyGameObjects(PowerNode.class)) {
			MapLocation nodeLoc = node.getLocation();
			short id = getPowerNodeID(nodeLoc);
			if (powerNodeGraph.nodeSensed[id])
				continue;

			if (id == 0) {
				powerNodeGraph.nodeCount++;
				id = powerNodeGraph.nodeCount;
				powerNodeGraph.nodeLocations[id] = nodeLoc;
				int x = worldToCacheX(nodeLoc.x);
				int y = worldToCacheY(nodeLoc.y);
				insertArtificialWall(x, y);
				powerNodeID[x][y] = id;
			}
			if (node.powerCoreTeam() != null
					&& node.powerCoreTeam() != br.myTeam) {
				powerNodeGraph.enemyPowerCoreID = id;
			}
			for (MapLocation neighborLoc : node.neighbors()) {
				short neighborID = getPowerNodeID(neighborLoc);
				if (powerNodeGraph.nodeSensed[neighborID])
					continue;
				if (neighborID == 0) {
					powerNodeGraph.nodeCount++;
					neighborID = powerNodeGraph.nodeCount;
					powerNodeGraph.nodeLocations[neighborID] = neighborLoc;
					int x = worldToCacheX(neighborLoc.x);
					int y = worldToCacheY(neighborLoc.y);
					powerNodeID[x][y] = neighborID;
				}
				powerNodeGraph.adjacencyList[id][powerNodeGraph.degreeCount[id]++] = neighborID;
				powerNodeGraph.adjacencyList[neighborID][powerNodeGraph.degreeCount[neighborID]++] = id;
			}
			powerNodeGraph.nodeSensedCount++;
			powerNodeGraph.nodeSensed[id] = true;
		}
	}

	public void integratePowerNodes(int[] data) {
		int mask = (1 << 15) - 1;
		if (powerNodeGraph.enemyPowerCoreID == 0 && data[0] != 32001) {
			int coreX = data[0] >> 15;
			int coreY = data[0] & mask;
			short coreID = powerNodeID[worldToCacheX(coreX)][worldToCacheY(coreY)];
			if (coreID == 0) {
				powerNodeGraph.nodeCount++;
				coreID = powerNodeGraph.nodeCount;
				powerNodeGraph.nodeLocations[coreID] = new MapLocation(coreX,
						coreY);
				int x = worldToCacheX(coreX);
				int y = worldToCacheY(coreY);
				powerNodeID[x][y] = coreID;
			}
			powerNodeGraph.enemyPowerCoreID = coreID;
		}
		int nodeX = data[1] >> 15;
		int nodeY = data[1] & mask;
		MapLocation nodeLoc = new MapLocation(nodeX, nodeY);
		short id = br.mc.getPowerNodeID(nodeLoc);
		if (powerNodeGraph.nodeSensed[id])
			return;
		if (id == 0) {
			powerNodeGraph.nodeCount++;
			id = powerNodeGraph.nodeCount;
			powerNodeGraph.nodeLocations[id] = nodeLoc;
			int x = worldToCacheX(nodeX);
			int y = worldToCacheY(nodeY);
			insertArtificialWall(x, y);
			powerNodeID[x][y] = id;
		}
		for (int i = 2; i < data.length; i++) {
			int neighborX = data[i] >> 15;
			int neighborY = data[i] & mask;
			MapLocation neighborLoc = new MapLocation(neighborX, neighborY);
			short neighborID = getPowerNodeID(neighborLoc);
			if (powerNodeGraph.nodeSensed[neighborID])
				continue;
			if (neighborID == 0) {
				powerNodeGraph.nodeCount++;
				neighborID = powerNodeGraph.nodeCount;
				powerNodeGraph.nodeLocations[neighborID] = neighborLoc;
				int x = worldToCacheX(neighborX);
				int y = worldToCacheY(neighborY);
				powerNodeID[x][y] = neighborID;
			}
			powerNodeGraph.adjacencyList[id][powerNodeGraph.degreeCount[id]++] = neighborID;
			powerNodeGraph.adjacencyList[neighborID][powerNodeGraph.degreeCount[neighborID]++] = id;
		}
		powerNodeGraph.nodeSensedCount++;
		powerNodeGraph.nodeSensed[id] = true;
	}

	// Does this robot know about the terrain of the given map location?
	public boolean isSensed(MapLocation loc) {
		return sensed[worldToCacheX(loc.x)][worldToCacheY(loc.y)];
	}

	// Is the given map location a wall tile (or an off map tile)?
	// Will return false if the robot does not know.
	public boolean isWall(MapLocation loc) {
		return isWall[worldToCacheX(loc.x)][worldToCacheY(loc.y)];
	}

	// Is the given map location an off map tile?
	// Will return false if the robot does not know.
	public boolean isOffMap(MapLocation loc) {
		int x = worldToCacheX(loc.x);
		int y = worldToCacheY(loc.y);
		return edgeXMin != 0 && x <= edgeXMin || edgeXMax != 0 && x >= edgeXMax
				|| edgeYMin != 0 && y <= edgeYMin || edgeYMax != 0
				&& y >= edgeYMax;
	}

	// Gets the unique index of the power node at the given location for
	// PowerNodeGraph to use in its data structure. <br>
	// Returns 0 if there is no power node known to be there.
	private short getPowerNodeID(MapLocation loc) {
		return powerNodeID[worldToCacheX(loc.x)][worldToCacheY(loc.y)];
	}

	// Returns true if the robot knows of a power node at the given location.
	public boolean isPowerNode(MapLocation loc) {
		return getPowerNodeID(loc) != 0;
	}

	// Returns true if we know there is a dead end power node at the given
	// location.
	public boolean isDeadEndPowerNode(MapLocation loc) {
		int id = getPowerNodeID(loc);
		if (id == 0 || !powerNodeGraph.nodeSensed[id])
			return false;
		return powerNodeGraph.degreeCount[id] <= 1;
	}

	// Converts from world x coordinates to cache x coordinates.
	public int worldToCacheX(int worldX) {
		return worldX - powerCoreWorldX + POWER_CORE_POSITION;
	}

	// Converts from world x coordinates to cache x coordinates.
	public int cacheToWorldX(int cacheX) {
		return cacheX + powerCoreWorldX - POWER_CORE_POSITION;
	}

	// Converts from cache y coordinates to world y coordinates.
	public int worldToCacheY(int worldY) {
		return worldY - powerCoreWorldY + POWER_CORE_POSITION;
	}

	// Converts from cache y coordinates to world y coordinates.
	public int cacheToWorldY(int cacheY) {
		return cacheY + powerCoreWorldY - POWER_CORE_POSITION;
	}

	// Preemptive map arrays
	private static final int[][][] sensorRangeARCHON = new int[][][] { // ARCHON
	{ // NORTH
			{ -6, 0 }, { -5, -3 }, { -4, -4 }, { -3, -5 }, { -2, -5 },
					{ -1, -5 }, { 0, -6 }, { 1, -5 }, { 2, -5 }, { 3, -5 },
					{ 4, -4 }, { 5, -3 }, { 6, 0 }, }, { // NORTH_EAST
			{ -3, -5 }, { -2, -5 }, { 0, -6 }, { 0, -5 }, { 1, -5 }, { 2, -5 },
					{ 3, -5 }, { 3, -4 }, { 4, -4 }, { 4, -3 }, { 5, -3 },
					{ 5, -2 }, { 5, -1 }, { 5, 0 }, { 5, 2 }, { 5, 3 },
					{ 6, 0 }, }, { // EAST
			{ 0, -6 }, { 0, 6 }, { 3, -5 }, { 3, 5 }, { 4, -4 }, { 4, 4 },
					{ 5, -3 }, { 5, -2 }, { 5, -1 }, { 5, 1 }, { 5, 2 },
					{ 5, 3 }, { 6, 0 }, }, { // SOUTH_EAST
			{ -3, 5 }, { -2, 5 }, { 0, 5 }, { 0, 6 }, { 1, 5 }, { 2, 5 },
					{ 3, 4 }, { 3, 5 }, { 4, 3 }, { 4, 4 }, { 5, -3 },
					{ 5, -2 }, { 5, 0 }, { 5, 1 }, { 5, 2 }, { 5, 3 },
					{ 6, 0 }, }, { // SOUTH
			{ -6, 0 }, { -5, 3 }, { -4, 4 }, { -3, 5 }, { -2, 5 }, { -1, 5 },
					{ 0, 6 }, { 1, 5 }, { 2, 5 }, { 3, 5 }, { 4, 4 }, { 5, 3 },
					{ 6, 0 }, }, { // SOUTH_WEST
			{ -6, 0 }, { -5, -3 }, { -5, -2 }, { -5, 0 }, { -5, 1 }, { -5, 2 },
					{ -5, 3 }, { -4, 3 }, { -4, 4 }, { -3, 4 }, { -3, 5 },
					{ -2, 5 }, { -1, 5 }, { 0, 5 }, { 0, 6 }, { 2, 5 },
					{ 3, 5 }, }, { // WEST
			{ -6, 0 }, { -5, -3 }, { -5, -2 }, { -5, -1 }, { -5, 1 },
					{ -5, 2 }, { -5, 3 }, { -4, -4 }, { -4, 4 }, { -3, -5 },
					{ -3, 5 }, { 0, -6 }, { 0, 6 }, }, { // NORTH_WEST
			{ -6, 0 }, { -5, -3 }, { -5, -2 }, { -5, -1 }, { -5, 0 },
					{ -5, 2 }, { -5, 3 }, { -4, -4 }, { -4, -3 }, { -3, -5 },
					{ -3, -4 }, { -2, -5 }, { -1, -5 }, { 0, -6 }, { 0, -5 },
					{ 2, -5 }, { 3, -5 }, }, };
	private static final int[][][] sensorRangeSOLDIER = new int[][][] { // SOLDIER
	{ // NORTH
			{ -3, -1 }, { -2, -2 }, { -1, -3 }, { 0, -3 }, { 1, -3 },
					{ 2, -2 }, { 3, -1 }, }, { // NORTH_EAST
			{ -1, -3 }, { 0, -3 }, { 1, -3 }, { 1, -2 }, { 2, -2 }, { 2, -1 },
					{ 3, -1 }, { 3, 0 }, { 3, 1 }, }, { // EAST
			{ 1, -3 }, { 1, 3 }, { 2, -2 }, { 2, 2 }, { 3, -1 }, { 3, 0 },
					{ 3, 1 }, }, { // SOUTH_EAST
			{ -1, 3 }, { 0, 3 }, { 1, 2 }, { 1, 3 }, { 2, 1 }, { 2, 2 },
					{ 3, -1 }, { 3, 0 }, { 3, 1 }, }, { // SOUTH
			{ -3, 1 }, { -2, 2 }, { -1, 3 }, { 0, 3 }, { 1, 3 }, { 2, 2 },
					{ 3, 1 }, }, { // SOUTH_WEST
			{ -3, -1 }, { -3, 0 }, { -3, 1 }, { -2, 1 }, { -2, 2 }, { -1, 2 },
					{ -1, 3 }, { 0, 3 }, { 1, 3 }, }, { // WEST
			{ -3, -1 }, { -3, 0 }, { -3, 1 }, { -2, -2 }, { -2, 2 },
					{ -1, -3 }, { -1, 3 }, }, { // NORTH_WEST
			{ -3, -1 }, { -3, 0 }, { -3, 1 }, { -2, -2 }, { -2, -1 },
					{ -1, -3 }, { -1, -2 }, { 0, -3 }, { 1, -3 }, }, };
	private static final int[][][] sensorRangeSCOUT = new int[][][] { // SCOUT
	{ // NORTH
			{ -5, 0 }, { -4, -3 }, { -3, -4 }, { -2, -4 }, { -1, -4 },
					{ 0, -5 }, { 1, -4 }, { 2, -4 }, { 3, -4 }, { 4, -3 },
					{ 5, 0 }, }, { // NORTH_EAST
			{ -3, -4 }, { -2, -4 }, { 0, -5 }, { 0, -4 }, { 1, -4 }, { 2, -4 },
					{ 3, -4 }, { 3, -3 }, { 4, -3 }, { 4, -2 }, { 4, -1 },
					{ 4, 0 }, { 4, 2 }, { 4, 3 }, { 5, 0 }, }, { // EAST
			{ 0, -5 }, { 0, 5 }, { 3, -4 }, { 3, 4 }, { 4, -3 }, { 4, -2 },
					{ 4, -1 }, { 4, 1 }, { 4, 2 }, { 4, 3 }, { 5, 0 }, }, { // SOUTH_EAST
			{ -3, 4 }, { -2, 4 }, { 0, 4 }, { 0, 5 }, { 1, 4 }, { 2, 4 },
					{ 3, 3 }, { 3, 4 }, { 4, -3 }, { 4, -2 }, { 4, 0 },
					{ 4, 1 }, { 4, 2 }, { 4, 3 }, { 5, 0 }, }, { // SOUTH
			{ -5, 0 }, { -4, 3 }, { -3, 4 }, { -2, 4 }, { -1, 4 }, { 0, 5 },
					{ 1, 4 }, { 2, 4 }, { 3, 4 }, { 4, 3 }, { 5, 0 }, }, { // SOUTH_WEST
			{ -5, 0 }, { -4, -3 }, { -4, -2 }, { -4, 0 }, { -4, 1 }, { -4, 2 },
					{ -4, 3 }, { -3, 3 }, { -3, 4 }, { -2, 4 }, { -1, 4 },
					{ 0, 4 }, { 0, 5 }, { 2, 4 }, { 3, 4 }, }, { // WEST
			{ -5, 0 }, { -4, -3 }, { -4, -2 }, { -4, -1 }, { -4, 1 },
					{ -4, 2 }, { -4, 3 }, { -3, -4 }, { -3, 4 }, { 0, -5 },
					{ 0, 5 }, }, { // NORTH_WEST
			{ -5, 0 }, { -4, -3 }, { -4, -2 }, { -4, -1 }, { -4, 0 },
					{ -4, 2 }, { -4, 3 }, { -3, -4 }, { -3, -3 }, { -2, -4 },
					{ -1, -4 }, { 0, -5 }, { 0, -4 }, { 2, -4 }, { 3, -4 }, }, };
	private static final int[][][] sensorRangeDISRUPTER = new int[][][] { // DISRUPTER
	{ // NORTH
			{ -4, 0 }, { -3, -2 }, { -2, -3 }, { -1, -3 }, { 0, -4 },
					{ 1, -3 }, { 2, -3 }, { 3, -2 }, { 4, 0 }, }, { // NORTH_EAST
			{ -2, -3 }, { 0, -4 }, { 0, -3 }, { 1, -3 }, { 2, -3 }, { 2, -2 },
					{ 3, -2 }, { 3, -1 }, { 3, 0 }, { 3, 2 }, { 4, 0 }, }, { // EAST
			{ 0, -4 }, { 0, 4 }, { 2, -3 }, { 2, 3 }, { 3, -2 }, { 3, -1 },
					{ 3, 1 }, { 3, 2 }, { 4, 0 }, }, { // SOUTH_EAST
			{ -2, 3 }, { 0, 3 }, { 0, 4 }, { 1, 3 }, { 2, 2 }, { 2, 3 },
					{ 3, -2 }, { 3, 0 }, { 3, 1 }, { 3, 2 }, { 4, 0 }, }, { // SOUTH
			{ -4, 0 }, { -3, 2 }, { -2, 3 }, { -1, 3 }, { 0, 4 }, { 1, 3 },
					{ 2, 3 }, { 3, 2 }, { 4, 0 }, }, { // SOUTH_WEST
			{ -4, 0 }, { -3, -2 }, { -3, 0 }, { -3, 1 }, { -3, 2 }, { -2, 2 },
					{ -2, 3 }, { -1, 3 }, { 0, 3 }, { 0, 4 }, { 2, 3 }, }, { // WEST
			{ -4, 0 }, { -3, -2 }, { -3, -1 }, { -3, 1 }, { -3, 2 },
					{ -2, -3 }, { -2, 3 }, { 0, -4 }, { 0, 4 }, }, { // NORTH_WEST
			{ -4, 0 }, { -3, -2 }, { -3, -1 }, { -3, 0 }, { -3, 2 },
					{ -2, -3 }, { -2, -2 }, { -1, -3 }, { 0, -4 }, { 0, -3 },
					{ 2, -3 }, }, };
	private static final int[][][] sensorRangeSCORCHER = new int[][][] { // SCORCHER
	{ // NORTH
			{ -2, -2 }, { -1, -3 }, { 0, -3 }, { 1, -3 }, { 2, -2 }, }, { // NORTH_EAST
			{ -1, -3 }, { 0, -3 }, { 1, -3 }, { 1, -2 }, { 2, -2 }, { 2, -1 },
					{ 3, -1 }, { 3, 0 }, { 3, 1 }, }, { // EAST
			{ 2, -2 }, { 2, 2 }, { 3, -1 }, { 3, 0 }, { 3, 1 }, }, { // SOUTH_EAST
			{ -1, 3 }, { 0, 3 }, { 1, 2 }, { 1, 3 }, { 2, 1 }, { 2, 2 },
					{ 3, -1 }, { 3, 0 }, { 3, 1 }, }, { // SOUTH
			{ -2, 2 }, { -1, 3 }, { 0, 3 }, { 1, 3 }, { 2, 2 }, }, { // SOUTH_WEST
			{ -3, -1 }, { -3, 0 }, { -3, 1 }, { -2, 1 }, { -2, 2 }, { -1, 2 },
					{ -1, 3 }, { 0, 3 }, { 1, 3 }, }, { // WEST
			{ -3, -1 }, { -3, 0 }, { -3, 1 }, { -2, -2 }, { -2, 2 }, }, { // NORTH_WEST
			{ -3, -1 }, { -3, 0 }, { -3, 1 }, { -2, -2 }, { -2, -1 },
					{ -1, -3 }, { -1, -2 }, { 0, -3 }, { 1, -3 }, }, };
}
