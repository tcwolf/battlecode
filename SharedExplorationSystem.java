package tcwolf;

import battlecode.common.Clock;
import battlecode.common.MapLocation;

public class SharedExplorationSystem {
	final BaseRobot br;
	final MapCacheSystem mc;
	public SharedExplorationSystem(BaseRobot br) {
		this.br = br;
		this.mc = br.mc;
	}
	
	//Broadcast a robot's knowledge of part of the map.
	public void broadcastMapFragment() {
		int startRow;
		int numRowBlocks;
		int startCol;
		int numColBlocks;
		
		if(Clock.getRoundNum()/6%2==0) {
			if(br.mc.edgeXMin!=0) {
				startCol = (br.mc.edgeXMin+1)/MapCacheSystem.MAP_BLOCK_SIZE;
				if(br.mc.edgeXMax!=0) {
					numColBlocks = br.mc.edgeXMax/MapCacheSystem.MAP_BLOCK_SIZE-(br.mc.edgeXMin+1)/MapCacheSystem.MAP_BLOCK_SIZE+1;
				} else {
					numColBlocks = 16;
				}
			} else if(br.mc.edgeXMax!=0) {
				numColBlocks = 16;
				startCol = br.mc.edgeXMax/MapCacheSystem.MAP_BLOCK_SIZE-numColBlocks+1;
			} else {
				startCol = 0;
				numColBlocks = 64;
			}
			
			if(br.mc.edgeYMin!=0) {
				startRow = (br.mc.edgeYMin+1)/MapCacheSystem.MAP_BLOCK_SIZE;
				if(br.mc.edgeYMax!=0) {
					numRowBlocks = br.mc.edgeYMax/MapCacheSystem.MAP_BLOCK_SIZE-(br.mc.edgeYMin+1)/MapCacheSystem.MAP_BLOCK_SIZE+1;
				} else {
					numRowBlocks = 16;
				}
			} else if(br.mc.edgeYMax!=0) {
				numRowBlocks = 16;
				startRow = br.mc.edgeYMax/MapCacheSystem.MAP_BLOCK_SIZE-numRowBlocks+1;
			} else {
				startRow = 0;
				numRowBlocks = 64;
			}
		} else {
			int rotation = (int)(Clock.getRoundNum()/12%4);
			startRow = br.mc.worldToCacheY(br.curLoc.y)/MapCacheSystem.MAP_BLOCK_SIZE - (rotation/2*2);
			startCol = br.mc.worldToCacheX(br.curLoc.x)/MapCacheSystem.MAP_BLOCK_SIZE - (rotation%2*2);
			numRowBlocks = 3;
			numColBlocks = 3;
		}
		int xb = startCol + ((Clock.getRoundNum()/12+br.myID) % numColBlocks);
		
		int[] buffer = new int[256];
		int c=0;
		for(int yb=startRow; yb<startRow+numRowBlocks; yb++) {
			int data = br.mc.packedSensed[xb][yb];
			if(data % 65536 == 0) continue;
			buffer[c++] = br.mc.packedIsWall[xb][yb];
			buffer[c++] = data;
		}
		if(c>0) {
			int[] ints = new int[c];
			System.arraycopy(buffer, 0, ints, 0, c);
			br.io.sendUInts(BroadcastChannel.EXPLORERS, BroadcastType.MAP_FRAGMENTS, ints);
		}
	}
	//Broadcasts robot's knowledge of the four map edges.
	public void broadcastMapEdges() {
		int[] edges = new int[] {
				br.mc.edgeXMin, 
				br.mc.edgeXMax,
				br.mc.edgeYMin,
				br.mc.edgeYMax
		};
		br.io.sendUShorts(BroadcastChannel.ALL, BroadcastType.MAP_EDGES, edges);
	}
	//Broadcasts data about one node in the power node graph and its neighbors.
	public void broadcastPowerNodeFragment() {
		PowerNodeGraph png = br.mc.powerNodeGraph;
		int id = (Clock.getRoundNum() % (png.nodeCount-1)) + 2;
		if(!png.nodeSensed[id]) return;
		int degree = png.degreeCount[id];
		int[] ints = new int[degree+2];
		if(png.enemyPowerCoreID!=0) {
			MapLocation loc = png.nodeLocations[png.enemyPowerCoreID];
			ints[0] = (loc.x << 15) + loc.y;
		} else {
			ints[0] = 32001;
		}
		ints[1] = (png.nodeLocations[id].x << 15) + png.nodeLocations[id].y;
		for(int i=0; i<degree; i++) {
			int neighborID = png.adjacencyList[id][i];
			ints[i+2] = (png.nodeLocations[neighborID].x << 15) + png.nodeLocations[neighborID].y;
		}
		br.io.sendUInts(BroadcastChannel.EXPLORERS, BroadcastType.POWERNODE_FRAGMENTS, ints);
	}
	
	//Receive data equivalent to one broadcast of a map fragment. 
	public void receiveMapFragment(int[] data) {
		for(int i=0; i<data.length; i+=2) {
			br.mc.integrateTerrainInfo(data[i], data[i+1]);
		}
	}
	//Receive data equivalent to one broadcast of the four map edges.
	public void receiveMapEdges(int[] data) {
		if(br.mc.edgeXMin==0) br.mc.edgeXMin = data[0];
		if(br.mc.edgeXMax==0) br.mc.edgeXMax = data[1];
		if(br.mc.edgeYMin==0) br.mc.edgeYMin = data[2];
		if(br.mc.edgeYMax==0) br.mc.edgeYMax = data[3];
	}
	//Receive data equivalent to one broadcast of a power node fragment.
	public void receivePowerNodeFragment(int[] data) {
		br.mc.integratePowerNodes(data);
	}
}
