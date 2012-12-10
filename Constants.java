package tcwolf;

import battlecode.common.Direction;

//A dump class to throw constants.
//TODO:  Add more
public final class Constants {

	//Reverse position mappings 
	public static final Direction[] directions = Direction.values();

	//Numbers of rounds of not seeing an enemy before resetting targets
	public static final int ENEMY_SPOTTED_SIGNAL_TIMEOUT = 50;

	//Rounds before the end of the game to go into emergency mode
	public static final int ENDGAME_CAP_MODE_BUFFER = 2000;
	
}