import java.io.*;
import java.util.*;

/**
 * Java port of the official bosses (config/level1/Boss.cs = Wood 2, config/level2/Boss.cs = Wood 1,
 * config/level3/Boss.cs = Bronze and Silver, the two leagues use the same boss).
 * Usage: java Boss 1|2|3
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

        private static boolean ccw(Node a, Node b, Node c) {
            return (c.y - a.y) * (b.x - a.x) > (b.y - a.y) * (c.x - a.x);
        }

        private static boolean isCrossing(Node n1a, Node n1b, Node n2a, Node n2b) {
            Set<Node> nodes = new HashSet<>();
            nodes.add(n1a);
            nodes.add(n1b);
            nodes.add(n2a);
            nodes.add(n2b);
            if (nodes.size() < 4) return false;

            // ccw(n1a, n1b, n1a) (always false) is a bug of the original, kept as is
            return ccw(n1a, n2a, n2b) != ccw(n1b, n2a, n2b) && ccw(n1a, n1b, n1a) != ccw(n1a, n1b, n2b);
        }

        boolean canConnect(Node to, List<Node> graph) {
            if (pythDist(to) > 400) return false;
            for (Node n2a : graph) {
                for (Node n2b : n2a.neighbors) {
                    if (isCrossing(this, to, n2a, n2b)) return false;
                }
            }
            return true;
        }

        private double pythDist(Node to) {
            int dx = x - to.x;
            int dy = y - to.y;
            return Math.sqrt(dx * dx + dy * dy);
        }

        public String toString() {
            return String.valueOf(id);
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

        // level 3 only: level 2 tries the first order alone (its source comments the five others out)
        List<List<Node>> cornerPermutations() {
            return Arrays.asList(
                    Arrays.asList(node1, node2, node3),
                    Arrays.asList(node1, node3, node2),
                    Arrays.asList(node2, node1, node3),
                    Arrays.asList(node2, node3, node1),
                    Arrays.asList(node3, node1, node2),
                    Arrays.asList(node3, node2, node1));
        }

        public String toString() {
            return node1.id + " " + node2.id + " " + node3.id;
        }

        String spawn(Node spawn) {
            StringJoiner others = new StringJoiner(" ");
            for (Node c : corners()) if (c != spawn) others.add(c.toString());
            return "SPAWN " + spawn + " " + others;
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

            if (level >= 3) {
                System.out.println(level3Turn(nodes, triangles, myUnitCells, random));
                continue;
            }

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

    // level 3: the game loop of config/level3/Boss.cs after the input is read. random is only used by mutate, like the static Random of the original
    static String level3Turn(List<Node> nodes, List<Triangle> triangles, List<Node> myUnitCells, Random random) {
        String solution = "";
        if (myUnitCells.size() > 20) {
            List<Triangle> myTriangles = new ArrayList<>();
            for (Triangle t : triangles) if (t.owner == 0 && t.corners().stream().allMatch(c -> c.myUnits > 0)) myTriangles.add(t);
            if (myTriangles.size() > 0) {
                Triangle attackSource = null;
                int bestThreat = -1;
                for (Triangle t : myTriangles) {
                    int threat = t.neighbors().stream().mapToInt(n -> n.opponentUnits).max().getAsInt();
                    if (threat > bestThreat) {
                        bestThreat = threat;
                        attackSource = t;
                    }
                }
                Node attack = null;
                for (Node n : attackSource.neighbors()) if (attack == null || n.opponentUnits > attack.opponentUnits) attack = n;
                if (attack.opponentUnits > 1) {
                    solution = "ATTACK " + attackSource + " " + attack.id + ";MSG ATTACK!;";
                }
                // unused, as in the original (its only use is commented out there), kept because First() throws on an empty sequence
                List<Node> sourceCorners = attackSource.corners();
                Node removeEdge = attackSource.neighbors().stream().distinct().filter(n -> !sourceCorners.contains(n))
                        .sorted(Comparator.comparingInt((Node n) -> n.opponentUnits).reversed()).findFirst().get();
                for (Node corner : attackSource.corners()) {
                    if (!solution.isEmpty()) break;
                    for (Node partner : nodes) {
                        if (partner.dist[corner.id] != 2) continue;
                        if (corner.canConnect(partner, nodes)) {
                            List<Node> others = new ArrayList<>(attackSource.corners());
                            others.remove(corner);
                            solution = "ADD_PATH " + corner + " " + others.get(0) + " " + others.get(1) + " " + partner + ";";
                            for (Node c : attackSource.corners()) myUnitCells.remove(c);
                            for (Node c : attackSource.corners()) c.myUnits--;
                            attackSource.owner = -1;
                            break;
                        }
                    }
                }
            }
        }

        int bestCost = 1000;
        List<Node> moveFrom = new ArrayList<>();
        List<Node> moveTo = new ArrayList<>();
        if (myUnitCells.size() >= 3) {
            for (Triangle triangle : triangles) {
                if (triangle.owner == 0 || !triangle.meCanCapture) continue;
                for (List<Node> corners : triangle.cornerPermutations()) {
                    List<Node> sources = new ArrayList<>();
                    List<Node> free = new ArrayList<>(myUnitCells);
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
                        moveFrom = new ArrayList<>(sources);
                        moveTo = new ArrayList<>(corners);
                    }
                }
            }
        }
        List<Node> targets = mutate(triangles, myUnitCells, moveFrom, moveTo, random);
        StringJoiner moves = new StringJoiner(";");
        for (int i = 0; i < myUnitCells.size(); i++) moves.add(myUnitCells.get(i).move(targets.get(i)));
        solution += moves;

        int unitCount = myUnitCells.size();
        for (Triangle triangle : triangles) {
            if (triangle.owner != 0) continue;
            if (unitCount++ < nodes.size()) {
                // OrderBy(...).ThenByDescending(...) is a stable sort, like List.sort
                List<Node> corners = new ArrayList<>(triangle.corners());
                corners.sort(Comparator.comparingLong((Node c) -> targets.stream().filter(t -> t == c).count())
                        .thenComparing(c -> c.neighbors.size(), Comparator.reverseOrder()));
                double score = Integer.MIN_VALUE;
                Node spawn = corners.get(0);
                for (Node corner : corners) {
                    targets.add(corner);
                    double tmpScore = score(triangles, targets);
                    targets.remove(targets.size() - 1);
                    if (tmpScore > score) {
                        spawn = corner;
                        score = tmpScore;
                    }
                }
                solution += ";" + triangle.spawn(spawn);
                targets.add(spawn);
            }
        }
        return solution;
    }

    static List<Node> mutate(List<Triangle> triangles, List<Node> myUnits, List<Node> moveFrom, List<Node> moveTo, Random random) {
        List<Node> targets = new ArrayList<>();
        for (Node n : myUnits) targets.add(null);
        for (int i = 0; i < moveFrom.size(); i++) {
            for (int j = 0; j < targets.size(); j++) {
                if (targets.get(j) == null && myUnits.get(j) == moveFrom.get(i)) {
                    targets.set(j, moveTo.get(i));
                    break;
                }
            }
        }

        for (int i = 0; i < targets.size(); i++) {
            if (targets.get(i) == null) targets.set(i, myUnits.get(i).neighbors.get(random.nextInt(myUnits.get(i).neighbors.size())));
        }
        double score = score(triangles, targets);
        for (int mutation = 0; mutation < 10000; mutation++) {
            // with no unit left, the original crashes here (Next(0) returns 0, then targets[0] throws): nextInt(0) throws as well
            int index = random.nextInt(targets.size());
            Node backup = targets.get(index);
            Node newNode = myUnits.get(index).neighbors.get(random.nextInt(myUnits.get(index).neighbors.size()));
            targets.set(index, newNode);
            double newScore = score(triangles, targets);
            if (newScore >= score) score = newScore;
            else targets.set(index, backup);
        }

        return targets;
    }

    static double score(List<Triangle> triangles, List<Node> moveTo) {
        double result = 0;
        int[] unitCount = new int[50];
        for (Node to : moveTo) unitCount[to.id]++;
        for (Triangle t : triangles) {
            if (t.meCanCapture && unitCount[t.node1.id] > 0 && unitCount[t.node2.id] > 0 && unitCount[t.node3.id] > 0) result++;
            else if (t.owner != 0 && !t.meCanCapture && unitCount[t.node1.id] == 0 && unitCount[t.node2.id] == 0 && unitCount[t.node3.id] == 0) result += 0.5;
        }
        return result;
    }
}
