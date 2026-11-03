import java.io.*;
import java.util.*;

/**
 * Java port of the official bosses (config/level1/Boss.cs = Wood 2, config/level2/Boss.cs = Wood 1).
 * Usage: java Boss 1|2
 */
class Boss {
    static class Node {
        int id, x, y;
        List<Node> neighbors = new ArrayList<>();
        int myUnits, opponentUnits;
        int[] dist = new int[50];

        Node(int id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        void clear() {
            neighbors.clear();
            myUnits = 0;
            opponentUnits = 0;
            Arrays.fill(dist, 1000);
        }

        void computeDist() {
            ArrayDeque<Node> queue = new ArrayDeque<>();
            queue.add(this);
            dist[id] = 0;
            while (!queue.isEmpty()) {
                Node current = queue.poll();
                for (Node next : current.neighbors) {
                    if (dist[next.id] != 1000) continue;
                    dist[next.id] = 1 + dist[current.id];
                    queue.add(next);
                }
            }
        }

        String move(Node target) {
            if (this == target) return "";
            Node best = null;
            for (Node n : neighbors) if (best == null || n.dist[target.id] < best.dist[target.id]) best = n;
            return "MOVE " + id + " " + best.id + " 1";
        }
    }

    static class Triangle {
        Node node1, node2, node3;
        int owner;
        boolean meCanCapture;

        List<Node> corners() {
            return Arrays.asList(node1, node2, node3);
        }

        List<Node> neighbors() {
            List<Node> result = new ArrayList<>();
            for (Node c : corners()) result.addAll(c.neighbors);
            return result;
        }

        public String toString() {
            return node1.id + " " + node2.id + " " + node3.id;
        }
    }

    public static void main(String[] args) throws IOException {
        int level = args.length > 0 ? Integer.parseInt(args[0]) : 1;
        Random random = new Random(0);
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in));
        int nodeCount = Integer.parseInt(in.readLine().trim());
        List<Node> nodes = new ArrayList<>();
        for (int i = 0; i < nodeCount; i++) {
            String[] inputs = in.readLine().trim().split(" ");
            nodes.add(new Node(Integer.parseInt(inputs[0]), Integer.parseInt(inputs[1]), Integer.parseInt(inputs[2])));
        }

        while (true) {
            String scoreLine = in.readLine();
            if (scoreLine == null) return;
            for (Node n : nodes) n.clear();
            for (int i = 0; i < nodeCount; i++) {
                String[] nums = in.readLine().trim().split(" ");
                Node node = nodes.get(Integer.parseInt(nums[0]));
                node.myUnits = Integer.parseInt(nums[1]);
                node.opponentUnits = Integer.parseInt(nums[2]);
            }
            int linkCount = Integer.parseInt(in.readLine().trim());
            for (int i = 0; i < linkCount; i++) {
                String[] inputs = in.readLine().trim().split(" ");
                Node n1 = nodes.get(Integer.parseInt(inputs[0]));
                Node n2 = nodes.get(Integer.parseInt(inputs[1]));
                n1.neighbors.add(n2);
                n2.neighbors.add(n1);
            }
            int triangleCount = Integer.parseInt(in.readLine().trim());
            List<Triangle> triangles = new ArrayList<>();
            for (int i = 0; i < triangleCount; i++) {
                String[] inputs = in.readLine().trim().split(" ");
                Triangle t = new Triangle();
                t.node1 = nodes.get(Integer.parseInt(inputs[0]));
                t.node2 = nodes.get(Integer.parseInt(inputs[1]));
                t.node3 = nodes.get(Integer.parseInt(inputs[2]));
                t.owner = Integer.parseInt(inputs[3]);
                t.meCanCapture = !inputs[4].equals("0");
                triangles.add(t);
            }
            int linkableCount = Integer.parseInt(in.readLine().trim());
            for (int i = 0; i < linkableCount; i++) in.readLine();

            for (Node n : nodes) n.computeDist();

            List<Node> myUnitCells = new ArrayList<>();
            for (Node node : nodes) for (int i = 0; i < node.myUnits; i++) myUnitCells.add(node);

            int bestCost = 1000;
            String solution = "WAIT";
            if (level >= 2 && myUnitCells.size() > 10) {
                Triangle attackSource = null;
                int bestThreat = -1;
                for (Triangle t : triangles) {
                    if (t.owner != 0 || t.corners().stream().anyMatch(c -> c.myUnits == 0)) continue;
                    int threat = t.neighbors().stream().mapToInt(n -> n.opponentUnits).max().getAsInt();
                    if (threat > bestThreat) {
                        bestThreat = threat;
                        attackSource = t;
                    }
                }
                if (attackSource != null) {
                    Node attack = null;
                    for (Node n : attackSource.neighbors()) if (attack == null || n.opponentUnits > attack.opponentUnits) attack = n;
                    if (attack.opponentUnits > 1) solution = "ATTACK " + attackSource + " " + attack.id + ";MSG ATTACK!;";
                }
            }

            List<Node> moving = new ArrayList<>();
            if (myUnitCells.size() >= 3) {
                List<Node> takeUnits = new ArrayList<>(myUnitCells);
                if (level == 1) while (takeUnits.size() > 3) takeUnits.remove(random.nextInt(takeUnits.size()));
                for (Triangle triangle : triangles) {
                    if (triangle.owner == 0 || !triangle.meCanCapture) continue;
                    List<Node> corners = triangle.corners();
                    List<Node> sources = new ArrayList<>();
                    List<Node> free = new ArrayList<>(takeUnits);
                    int currentScore = 0;
                    for (Node target : corners) {
                        int index = 0;
                        for (int i = 1; i < free.size(); i++) {
                            if (target.dist[free.get(i).id] < target.dist[free.get(index).id]) index = i;
                        }
                        sources.add(free.get(index));
                        free.remove(index);
                        currentScore = Math.max(currentScore, sources.get(sources.size() - 1).dist[target.id]);
                    }
                    if (currentScore < bestCost) {
                        bestCost = currentScore;
                        StringJoiner joiner = new StringJoiner(";");
                        for (int i = 0; i < 3; i++) joiner.add(sources.get(i).move(corners.get(i)));
                        solution = joiner.toString();
                        moving = sources;
                    }
                }
            }

            if (myUnitCells.size() < nodes.size()) {
                for (Triangle triangle : triangles) {
                    if (triangle.owner != 0) continue;
                    solution += ";SPAWN " + triangle.node1.id + " " + triangle.node2.id + " " + triangle.node3.id;
                    break;
                }
            }
            for (Node node : moving) myUnitCells.remove(node);
            for (Node unit : myUnitCells) solution += ";MOVE " + unit.id + " " + unit.neighbors.get(random.nextInt(unit.neighbors.size())).id + " 1";

            System.out.println(solution);
        }
    }
}
