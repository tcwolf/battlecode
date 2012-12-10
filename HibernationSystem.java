package tcwolf;

import battlecode.common.GameActionException;
import battlecode.common.MapLocation;
import battlecode.common.Message;
import battlecode.common.RobotController;

public class HibernationSystem {
	final BaseRobot br;
	
	private boolean lowFluxMode;
	
	public static final int MODE_NORMAL = 0xA;
	public static final int MODE_LOW_FLUX = 0xB;
	
	public static final int EXIT_MESSAGED = 0x0;
	public static final int EXIT_ATTACKED = 0x1;
	public static final int EXIT_REFUELED = 0x2;


	public HibernationSystem(BaseRobot baseRobot) {
		this.br = baseRobot;
		lowFluxMode = false;
	}
	
	public void setMode(int mode) {
		switch(mode) {
		case MODE_NORMAL:
			lowFluxMode = false;
			break;
		case MODE_LOW_FLUX:
			lowFluxMode = true;
			break;
		default:
//			System.out.println("ERROR");
			break;
		}
		
	}

	//Enable Hibernation mode
	public int run() {

		// LOCAL VARIABLE DECLARATION ORDER
		int i; 							// istore_1
		int[] mints; 					// astore_2
		Message[] msgs;	 				// astore_3

		RobotController rc = br.rc; 	// field 4
		int time=0;						// field 5
		boolean localLowFluxMode = this.lowFluxMode;
	
		double lastEnergon = rc.getEnergon();
		double lastFlux = rc.getFlux();
		double curEnergon, curFlux;
		MapLocation curLoc = rc.getLocation();

		// team number check
		int teamkey = br.io.teamkey;
		
		
		// generate our help message
		Message helpMsg = new Message();
		
		String data = 
				BroadcastChannel.SCOUTS.chanHeader.concat(
				BroadcastType.LOW_FLUX_HELP.header_s).concat(
				br.io.generateMetadata());
		helpMsg.ints = new int[]{
				br.io.teamkey,
				br.io.hashMessage(new StringBuilder(data)),
		};
		helpMsg.strings = new String[]{data};
		

		while (true) {
			
			// emergency wakeup conditions
			if((curEnergon = rc.getEnergon()) < lastEnergon) {
				br.resetClock();
				br.updateRoundVariables();
				br.io.sendWakeupCall();
				return EXIT_ATTACKED;
			}
			if((curFlux = rc.getFlux()) < lastFlux - 1.0) {
				br.resetClock();
				br.updateRoundVariables();
				br.io.sendWakeupCall();
				return EXIT_ATTACKED;
			}      
			
			// low flux mode special effects
			if(localLowFluxMode) {
				
				// wakeup on being refueled
				if(curFlux > lastFlux) {
					br.resetClock();
					br.updateRoundVariables();
					return EXIT_REFUELED;
				}
				
				// send a help message
				if(time++ % 50 == 0) {
					try{
						rc.broadcast(helpMsg);
					} catch(GameActionException e) {
						//No problem
					}
				}
			}
			
			lastEnergon = curEnergon;
			lastFlux = curFlux;
			
			// check for awaken message
			msgs = rc.getAllMessages();
			for (i = msgs.length; --i >= 0;) {

				// validity check
				if ((mints = msgs[i].ints) == null)
					continue;
				if (mints.length != 3)
					continue;
				if (mints[0] != teamkey)
					continue;
				
				// only units low on flux ignore far away messages
				if (localLowFluxMode && curLoc.distanceSquaredTo(BroadcastSystem.intToLoc(mints[2])) > 5)
					continue;
				
				br.resetClock();
				br.updateRoundVariables();
				br.io.sendWakeupCall();
				return EXIT_MESSAGED; // our exit point
			}
			//Yield remaining bytecode
			rc.yield();
		}
	}
}
