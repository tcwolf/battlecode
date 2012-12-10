package tcwolf;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.KeyEvent;
import java.awt.event.KeyListener;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;

public class NormalBugTestUI extends JFrame {
	private static final long serialVersionUID = 346L;

	final static int[][] ddd = new int[8][2];
	static {
		for (int i = 0; i < 8; i++) {
			ddd[i][0] = Constants.directions[i].dx;
			ddd[i][1] = Constants.directions[i].dy;
		}
	}

	public int movesPerSecond = 5;
	public int mapSize = 30;
	public int tilePixels = 25;
	public boolean[][] map;
	public int numAlgos = 3;
	public int[] px, py;
	public int qx, qy;
	public boolean[] keysHeld;
	public Thread mainThread;
	public boolean running;
	public int moveCount;
	NormalBug normalBug;

	public NormalBugTestUI() {
		keysHeld = new boolean[65536];
		px = new int[numAlgos];
		py = new int[numAlgos];

		for (int n = 0; n < 256; n++)
			keysHeld[n] = false;
		map = new boolean[mapSize][mapSize];
		normalBug = new NormalBug();
		JComponent component = new JComponent() {
			private static final long serialVersionUID = 41243L;

			@Override
			public Dimension getPreferredSize() {
				return new Dimension(mapSize * tilePixels, mapSize * tilePixels);
			}

			@Override
			public void paintComponent(Graphics g) {
				for (int x = 0; x < mapSize; x++)
					for (int y = 0; y < mapSize; y++) {
						g.setColor(map[x][y] ? Color.black : Color.white);
						g.fillRect(x * tilePixels, y * tilePixels, tilePixels,
								tilePixels);
					}
				g.setColor(Color.green);
				g.fillRect(qx * tilePixels, qy * tilePixels, tilePixels,
						tilePixels);
				g.setColor(Color.red);
				g.fillRect(px[0] * tilePixels + tilePixels / 3, py[0]
						* tilePixels + tilePixels / 3, tilePixels / 2,
						tilePixels / 2);
				g.setColor(Color.blue);
				g.fillRect(px[1] * tilePixels + tilePixels / 5, py[1]
						* tilePixels + tilePixels / 5, tilePixels / 2,
						tilePixels / 2);
				g.setColor(Color.pink);
				g.fillRect(px[2] * tilePixels + tilePixels / 7, py[2]
						* tilePixels + tilePixels / 7, tilePixels / 2,
						tilePixels / 2);
			}
		};
		this.add(component, BorderLayout.CENTER);
		this.add(new JLabel("space: pause/resume, " + "lmb: set destination, "
				+ "rmb: draw walls, "
				+ "red is optimal BFS, blue is bugnav, pink is jven"),
				BorderLayout.PAGE_START);
		this.setDefaultCloseOperation(EXIT_ON_CLOSE);
		this.pack();
		component.setFocusable(true);
		component.addKeyListener(new KeyListener() {
			@Override
			public void keyPressed(KeyEvent e) {
				int key = e.getKeyCode();
				if (key == KeyEvent.VK_SPACE && !keysHeld[key]) {
					running = !running;
				}
				keysHeld[key] = true;
			}

			@Override
			public void keyReleased(KeyEvent e) {
				int key = e.getKeyCode();
				keysHeld[key] = false;
			}

			@Override
			public void keyTyped(KeyEvent e) {
			}
		});

		class MapMouseListener implements MouseListener, MouseMotionListener {

			/** -1 is not dragging, 0 is adding, 1 is erasing **/
			int dragging = -1;

			public void mouseClicked(MouseEvent e) {
			}

			public void mouseEntered(MouseEvent e) {
			}

			public void mouseExited(MouseEvent e) {
			}

			public void mousePressed(MouseEvent e) {
				int button = e.getButton();
				int x = (e.getX()) / tilePixels;
				int y = (e.getY()) / tilePixels;
				if (x < 0 || y < 0 || x >= mapSize || y >= mapSize)
					return;

				if (button == MouseEvent.BUTTON1) {
					if (map[x][y])
						return;
					qx = x;
					qy = y;
					normalBug.setTarget(qx, qy);
					setTitle(qx + " " + qy);
				}
				if (button == MouseEvent.BUTTON3) {
					dragging = map[x][y] ? 0 : 1;
					map[x][y] = !map[x][y];
				}
				repaint();
			}

			@Override
			public void mouseReleased(MouseEvent e) {
				dragging = -1;
				repaint();
			}

			public void mouseMoved(MouseEvent arg0) {
			}

			public void mouseDragged(MouseEvent e) {
				int x = (e.getX()) / tilePixels;
				int y = (e.getY()) / tilePixels;
				if (x < 0 || y < 0 || x >= mapSize || y >= mapSize)
					return;
				if (dragging == 0)
					map[x][y] = false;
				else if (dragging == 1)
					map[x][y] = true;
				repaint();
			}
		}

		MouseListener mouse_handler = new MapMouseListener();
		component.addMouseListener(mouse_handler);
		component.addMouseMotionListener((MouseMotionListener) mouse_handler);

		init();
		Thread mainThread = new Thread(new Runnable() {
			@Override
			public void run() {
				while (true) {
					try {
						Thread.sleep(1000 / movesPerSecond);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					if (running) {

						step();
					}
				}
			}
		});
		running = true;
		mainThread.start();
	}

	public void init() {
		for (int x = 0; x < mapSize; x++)
			for (int y = 0; y < mapSize; y++) {
				map[x][y] = false;
			}
		for (int i = 0; i < numAlgos; i++) {
			px[i] = 15;
			py[i] = 26;
			map[px[i]][py[i]] = false;
		}
		qx = 15;
		qy = 21;
		normalBug.setTarget(qx, qy);
		normalBug.edgeXMin = 0;
		normalBug.edgeXMax = mapSize - 1;
		normalBug.edgeYMin = 0;
		normalBug.edgeYMax = mapSize - 1;

		map[qx][qy] = false;
		moveCount = 0;

	}

	public void step() {
		int i = 0;
		boolean movable[] = new boolean[8];
		for (int j = 0; j < 8; j++) {
			int x = px[i] + ddd[j][0];
			int y = py[i] + ddd[j][1];
			movable[j] = !map[x][y];
		}
		int[] d = normalBug.computeMove(px[i], py[i], movable);
		if (d != null) {
			px[i] += d[0];
			py[i] += d[1];
		}
		moveCount++;
		repaint();
	}

	public static void main(String args[]) {
		NormalBugTestUI frame = new NormalBugTestUI();
		frame.setLocation(100, 30);
		frame.setVisible(true);
	}
}
