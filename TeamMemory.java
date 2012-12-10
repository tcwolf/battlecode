package tcwolf;

import battlecode.common.Clock;
import battlecode.common.RobotType;

/**
 * Archon memory between rounds. We have 32 longs available. Here is a list of
 * what each long represents:
 * <ul>
 * <li>0 = round number (0, 1, or 2), touched only by archon 0
 * <li>1 = enemies seen by archon 0
 * <li>2 = enemies seen by archon 1
 * <li>3 = enemies seen by archon 2
 * <li>4 = enemies seen by archon 3
 * <li>5 = enemies seen by archon 4
 * <li>6 = enemies seen by archon 5
 * <li>7 = the enemy team number
 * <li>8 = UNUSED
 * <li>9 = UNUSED
 * <li>10 = UNUSED
 * <li>11 - 31 = UNUSED
 * </ul>
 */
public class TeamMemory {

	// private vars
	private final BaseRobot br;
	private final long[] mem;
	private final boolean[] enemySeen;

	// current counts
	public int curSoldierCount;
	public int curScoutCount;
	public int curDisrupterCount;
	public int curScorcherCount;

	// count metadata
	public int timeCountWritten;

	public TeamMemory(BaseRobot br) {
		this.br = br;
		mem = br.rc.getTeamMemory();
		enemySeen = new boolean[4096];

		timeCountWritten = 0;
	}

	/** @return the current round number (0th, 1st, 2nd match) */
	public int getRound() {
		return (int) mem[0];
	}

	/** Archon zero should advance the round counter */
	public void advanceRound() {
		br.rc.setTeamMemory(0, getRound() + 1);
	}

	/**
	 * Call this method when you see an enemy. Next round's units will have
	 * access to the number of enemies seen in the previous round for each
	 * RobotType. Called in Radar.
	 * 
	 * @param archonID
	 *            - reporting archon's ID (0-5)
	 * @param enemyID
	 *            - reported enemy's ID
	 * @param type
	 *            - reported enemy's type
	 */
	public void countEnemy(int enemyID, RobotType type) {
		if (enemySeen[enemyID])
			return;

		enemySeen[enemyID] = true;
		switch (type) {
		case SOLDIER:
			curSoldierCount++;
			break;
		case SCOUT:
			curScoutCount++;
			break;
		case DISRUPTER:
			curDisrupterCount++;
			break;
		case SCORCHER:
			curScorcherCount++;
			break;
		default:
			break;
		}
	}

	/**
	 * Write our current enemy counts to file. Do when you have spare bytecodes
	 */
	public void writeEnemyCount() {
		br.rc.setTeamMemory(1 + br.myArchonID, ((long) curSoldierCount << 48)
				+ ((long) curScoutCount << 32)
				+ ((long) curDisrupterCount << 16) + (long) curScorcherCount);
		timeCountWritten = Clock.getRoundNum();
	}

	/**
	 * Returns the average number of enemies of the given RobotType in the
	 * previous round, over each Archon. Does not count Archons or Towers.
	 * Returns 0 if called the first round.
	 * 
	 * @param type
	 *            The RobotType to report
	 * @return The average number of enemies of the given type seen the previous
	 *         round, over each Archon.
	 */
	public void initReadEnemyCount() {
		int totalSoldiersSeen = 0;
		int totalScoutsSeen = 0;
		int totalDisruptersSeen = 0;
		int totalScorchersSeen = 0;
		for (int archonID = 0; archonID < 6; archonID++) {
			long archonReport = mem[1 + archonID];
			totalSoldiersSeen += (int) ((archonReport >> 48) & 0xFFFF);
			totalScoutsSeen += (int) ((archonReport >> 32) & 0xFFFF);
			totalDisruptersSeen += (int) ((archonReport >> 16) & 0xFFFF);
			totalScorchersSeen += (int) (archonReport & 0xFFFF);
		}

		// seed our initial w/ scaling
		curSoldierCount += (int) Math.ceil(totalSoldiersSeen / 30.0);
		curScoutCount += (int) Math.ceil(totalScoutsSeen / 30.0);
		curDisrupterCount += (int) Math.ceil(totalDisruptersSeen / 30.0);
		curScorcherCount += (int) Math.ceil(totalScorchersSeen / 30.0);
	}

	public void recordEnemyTeam(int teamNumber) {
		if (mem[7] == 0) {
			br.rc.setTeamMemory(7, teamNumber);
		}
	}

	public int getEnemyTeam() {
		return (int) mem[7];
	}
}
