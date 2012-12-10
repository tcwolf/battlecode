package tcwolf;

//Fast set for storing unsigned shorts
public class FastUShortSet {
	
	private final StringBuilder setContainer;

	public FastUShortSet() {
		setContainer = new StringBuilder();
	}

	//Adds an element to the set
	public void add(int i) {
		if(setContainer.indexOf(String.valueOf((char)i)) < 0){
			setContainer.append((char)i);
		}
	}

	//Pops an element out of the set.  
	//TODO:  Anticipate being empty; currently throws an error
	public int pop() {
		int res = setContainer.charAt(0);
		setContainer.deleteCharAt(0);
		return res;
	}

	//Delete value from the set
	public void remove(int i) {
		int idx;
		if((idx = setContainer.indexOf(String.valueOf((char)i)))>= 0){
			setContainer.deleteCharAt(idx);
		}
	}

	//Is the set empty?
	public boolean isEmpty() {
		return setContainer.length() == 0;
	}

	public String toString() {
		return setContainer.toString();
	}

	public int size() {
		return setContainer.length();
	}
	
	public int get(int index) {
		return setContainer.charAt(index);
	}
	
	public boolean contains(int i) {
		return setContainer.indexOf(String.valueOf((char)i)) >= 0;
	}
	
	
	public static void main(String[] args) {
		FastUShortSet a = new FastUShortSet();
		a.add(81); //Q
		a.add(81); //Q
		a.add(82); //R
		a.add(83); //S
		a.add(81); //Q
		a.pop();
		a.remove(82);
		System.out.println(a);
		System.out.println(a.contains(81));
	}
}
