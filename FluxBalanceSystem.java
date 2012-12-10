package tcwolf;

import battlecode.common.GameActionException;
import battlecode.common.RobotController;
import battlecode.common.RobotInfo;
import battlecode.common.RobotType;

public class FluxBalanceSystem {
	
	private enum FluxManagerMode {
		NO_TRANSFER,
		BATTERY,
		POOL_ARCHON
	}
	
	private final BaseRobot br;
	private final RobotController rc;
	private final RadarSystem radar;
	
	private FluxManagerMode mode;
	
	public FluxBalanceSystem(BaseRobot myBR) {
		br = myBR;
		rc = myBR.rc;
		radar = br.radar;
		mode = FluxManagerMode.POOL_ARCHON;
	}
	
	//Change robot to Battery mode
	public void setBatteryMode() {
		mode = FluxManagerMode.BATTERY;
	}
	
	//Change robot to Pool mode
	public void setPoolMode() {
		mode = FluxManagerMode.POOL_ARCHON;
	}

	//Disable running the flux balance management each turn
	public void disable() {
		mode = FluxManagerMode.NO_TRANSFER;
	}
	
	//Distrubution of flux
	public void manageFlux() throws GameActionException {
		switch (mode) {
		case BATTERY:
			if(br.myType == RobotType.ARCHON) {
				distributeArchonBattery();
			}
			break;
		case POOL_ARCHON:
			if(br.myType == RobotType.ARCHON) {
				distributeArchonPool();
			} else if(br.myType == RobotType.SCOUT) {
				distributeScoutPool();
			} else {
				distributeUnitPool();
			}
			break;
		default:
			break;
		}
	}
	
	private void distributeArchonBattery() throws GameActionException {
		if(rc.getFlux() > 10)
			distributeFluxBattle(rc.getFlux() - 0.1);
		if(rc.getFlux() <= 210) 
			return;
		double fluxToTransfer = rc.getFlux()-210;
		for(int n=0; n<radar.numAdjacentAllies && fluxToTransfer>0; n++) {
			RobotInfo ri = radar.adjacentAllies[n];
			if (ri.type == RobotType.TOWER)
				continue;
			
			double canHold = ri.type.maxFlux - ri.flux;
			if (ri.type == RobotType.ARCHON) 
				canHold -= 200;
			if (canHold > 1) {
				double x = Math.min(canHold, fluxToTransfer);
				rc.transferFlux(ri.location, ri.type.level, x);
				fluxToTransfer -= x;
			}
		}
	}
	
	private void distributeArchonPool() throws GameActionException {
		if (rc.getFlux() > 10)
			distributeFluxBattle(rc.getFlux()-0.1); // Save 0.1 flux for messaging
	}
	
	private void distributeScoutPool() throws GameActionException {
		// TODO implement moving to low flux units and giving them flux
		
		double myUpperFluxThreshold = (br.curEnergon < br.myMaxEnergon/2 ? 
				br.curEnergon/2 : br.curEnergon*2/3) + 5;
			
		distributeFluxBattle(rc.getFlux()-myUpperFluxThreshold);
	}
	
	private void distributeUnitPool() throws GameActionException {
		double myUpperFluxThreshold = (br.curEnergon < br.myMaxEnergon/2 ? 
				br.curEnergon/2 : br.curEnergon*2/3) + 5;
			
		distributeFluxBattle(rc.getFlux()-myUpperFluxThreshold);
	}
	
	private void distributeFluxBattle(double fluxToTransfer) throws GameActionException {
		if(fluxToTransfer<=0) return;
		
		br.radar.scan(true, false);
		
		// Compute amount that we need to transfer to put everyone above their lower threshold
		double amountBelowLowerThreshold = 0;
		for (int n=0; n<radar.numAdjacentAllies; n++) {
			RobotInfo ri = radar.adjacentAllies[n];
			if (ri.type == RobotType.TOWER || ri.type == RobotType.ARCHON)
				continue;
			double lowerFluxThreshold = (ri.energon < ri.type.maxEnergon/2 ? 
					ri.energon/4 : ri.energon/3) + 5;
			if(ri.flux < lowerFluxThreshold)
				amountBelowLowerThreshold += lowerFluxThreshold - ri.flux;
		}
		
		// If everyone is above their lower threshold, give to archons and scouts
		if(br.myType != RobotType.SCOUT && amountBelowLowerThreshold == 0) {
			if(br.myType != RobotType.ARCHON)
				for (int n=0; n<radar.numAdjacentAllies && fluxToTransfer>0; n++) {
					RobotInfo ri = radar.adjacentAllies[n];
					if (ri.type == RobotType.ARCHON && ri.flux < 280) {
						double x = Math.min(fluxToTransfer, 280-ri.flux);
						rc.transferFlux(ri.location, ri.type.level, x);
						fluxToTransfer -= x;
					}
				}
			for (int n=0; n<radar.numAdjacentAllies && fluxToTransfer>0; n++) {
				RobotInfo ri = radar.adjacentAllies[n];
				if (ri.type == RobotType.SCOUT && ri.flux<45) {
					double x = Math.min(fluxToTransfer, 45-ri.flux);
					rc.transferFlux(ri.location, ri.type.level, x);
					fluxToTransfer -= x;
				}
			}
			if(br.myType != RobotType.ARCHON && br.myType != RobotType.SCOUT) {
				for (int n=0; n<radar.numAdjacentAllies && fluxToTransfer>0; n++) {
					RobotInfo ri = radar.adjacentAllies[n];
					if (ri.type != RobotType.SCOUT && ri.type != RobotType.ARCHON) {
						double x = Math.min(fluxToTransfer, 5);
						rc.transferFlux(ri.location, ri.type.level, x);
						fluxToTransfer -= x;
					}
				}
			}
			
		// Otherwise, give flux to those that need it
		} else {
			for (int n=0; n<radar.numAdjacentAllies && fluxToTransfer>0; n++) {
				RobotInfo ri = radar.adjacentAllies[n];
				if (ri.type == RobotType.TOWER || ri.type == RobotType.ARCHON)
					continue;
				double lowerFluxThreshold = (ri.energon < ri.type.maxEnergon/2 ? 
						ri.energon/4 : ri.energon/3) + 5;
				if(ri.flux < lowerFluxThreshold) {
					double upperFluxThreshold = (ri.energon < ri.type.maxEnergon/2 ? 
							ri.energon/2 : ri.energon*2/3) + 5;
					double x = Math.min(fluxToTransfer, upperFluxThreshold - ri.flux);
					rc.transferFlux(ri.location, ri.type.level, x);
					fluxToTransfer -= x;
				}
			}
		}
	}
}
