package tcwolf;

import battlecode.common.Direction;
import battlecode.common.RobotType;

// A data structure containing a command to be processed by the movement state machine.
public class MoveInfo {
	public RobotType robotType;
	public Direction dir;
	public boolean moveForward;
	public boolean moveBackward;
	
	public MoveInfo(Direction dirToTurn) {
		this.dir = dirToTurn;
	}
	// Move in a direction. 
	public MoveInfo(Direction dirToMove, boolean moonwalk) {
		this.dir = dirToMove;
		if(moonwalk) moveBackward = true;
		else moveForward = true;
	}
	//Spawn a robot in a given direction.
	public MoveInfo(RobotType robotType, Direction dirToSpawn) {
		this.robotType = robotType;
		this.dir = dirToSpawn;
	}
	
	@Override
	public String toString() {
		if(dir==null || dir==Direction.NONE || dir==Direction.OMNI) return "Do nothing";
		if(robotType!=null) return "Spawn "+robotType+" to the "+dir;
		if(moveForward) return "Move forward to the "+dir;
		if(moveBackward) return "Move backward to the "+dir;
		return "Turn to the "+dir;
	}
}
