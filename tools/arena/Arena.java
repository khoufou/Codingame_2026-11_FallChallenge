import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

/**
 * Local arena for TryAngle Catch.
 * Engine = line-by-line port of the official referee (github.com/eulerscheZahl/TryAngle-Catch, engine package),
 * viewer code removed. Bots run as external processes speaking the CodinGame protocol.
 *
 * Usage, from the project root: java tools/arena/Arena.java -a BOT -b BOT [options]
 * BOT is a .java file (compiled automatically), boss1 (Wood 2 boss), boss2 (Wood 1 boss), boss3 (Bronze and Silver
 * boss) or a full command line. Leagues: 1 = Wood 2, 2 = Wood 1 (ATTACK), 3 = Bronze and above (ADD_PATH, REMOVE_PATH).
 * Options: -n games, -s firstSeed, -t threads, -l league, -swap (play each map from both sides), -v (one line per
 * game), -trace (turn by turn log), -build dir (compiled classes and bot error logs, default tools/arena/build),
 * -boss file (boss source, default tools/arena/Boss.java).
 */
public class Arena {
    static final int TOP_BORDER = 70;
    static final int BOTTOM_BORDER = 190;
    static final int SIDE_BORDER = 80;
    static final int WIDTH = 1920 - 2 * SIDE_BORDER;
    static final int HEIGHT = 1080 - TOP_BORDER - BOTTOM_BORDER;
    static final int NODE_COUNT = 50;
    static final int NODE_MIN_DIST = 140;
    static final double SPARSE_MIN = 0.1;
    static final double SPARSE_MAX = 0.5;
    static final int MIN_TRIANGLE_COUNT = 20;
    static final int MAX_PATH_LENGTH = 400;
    static final int MAX_ANGLE = 160;

    // ------------------------------------------------------------------ engine.Node
    static class Node {
        final int id, x, y;
        final ArrayList<Node> neighbors = new ArrayList<>();
        final int[] units = new int[2];
        final int[] remainingUnits = new int[2];

        Node(int id, int x, int y) {
            this.id = id;
            this.x = x;
            this.y = y;
        }

        Node mirror() {
            return new Node(id + 1, WIDTH - 1 - (x - SIDE_BORDER) + SIDE_BORDER, HEIGHT - 1 - (y - TOP_BORDER) + TOP_BORDER);
        }

        int dist2(Node node) {
            int dx = x - node.x, dy = y - node.y;
            return dx * dx + dy * dy;
        }

        double dist(Node node) {
            return Math.sqrt(dist2(node));
        }

        void finalizeTurn() {
            remainingUnits[0] = units[0];
            remainingUnits[1] = units[1];
        }

        int[] bfs(Node blocked) {
            int[] dist = new int[NODE_COUNT];
            Arrays.fill(dist, dist.length);
            dist[id] = 0;
            ArrayDeque<Node> queue = new ArrayDeque<>();
            queue.add(this);
            while (!queue.isEmpty()) {
                Node current = queue.poll();
                for (Node next : current.neighbors) {
                    if (dist[next.id] < dist.length || next == blocked) continue;
                    dist[next.id] = 1 + dist[current.id];
                    queue.add(next);
                }
            }
            return dist;
        }

        boolean canMoveTo(int p, Node target) {
            if (this == target) return false;
            if (remainingUnits[p] <= 0) return false;
            return target.bfs(null)[id] > 0;
        }

        void moveTo(int p, Node target, int amount) {
            if (this == target) return;
            amount = Math.min(amount, remainingUnits[p]);
            if (amount <= 0) return;
            int[] dist = target.bfs(null);
            for (Node next : neighbors) {
                if (dist[next.id] < dist[id]) {
                    units[p] -= amount;
                    remainingUnits[p] -= amount;
                    next.units[p] += amount;
                    return;
                }
            }
        }

        int owner() {
            if (!neighbors.isEmpty() && neighbors.stream().allMatch(n -> n.units[0] > n.units[1])) return 0;
            if (!neighbors.isEmpty() && neighbors.stream().allMatch(n -> n.units[1] > n.units[0])) return 1;
            if (units[0] > units[1]) return 0;
            if (units[1] > units[0]) return 1;
            return -1;
        }

        void addNeighbor(Node node) {
            if (neighbors.contains(node)) return;
            neighbors.add(node);
            neighbors.sort(Comparator.comparingInt(n -> n.id));
            node.addNeighbor(this);
        }

        void removeNeighbor(Node node) {
            neighbors.remove(node);
            node.neighbors.remove(this);
        }
    }

    // ------------------------------------------------------------------ engine.Edge
    static class Edge {
        final Node n1, n2;

        Edge(Node n1, Node n2) {
            this.n1 = n1;
            this.n2 = n2;
        }

        void makeNeighbors() {
            n1.addNeighbor(n2);
        }

        void unmakeNeighbors() {
            n1.removeNeighbor(n2);
        }

        static boolean ccw(Node a, Node b, Node c) {
            return (c.y - a.y) * (b.x - a.x) > (b.y - a.y) * (c.x - a.x);
        }

        boolean isCrossing(Edge edge) {
            if (n1 == n2) return false;
            if (n1 == edge.n1 || n2 == edge.n1 || n1 == edge.n2 || n2 == edge.n2) return false;
            return ccw(n1, edge.n1, edge.n2) != ccw(n2, edge.n1, edge.n2) && ccw(n1, n2, edge.n1) != ccw(n1, n2, edge.n2);
        }

        double length() {
            return n1.dist(n2);
        }

        boolean isBlocked(ArrayList<Edge> edges) {
            if (length() > MAX_PATH_LENGTH) return true;
            for (Edge l : edges) if (isCrossing(l)) return true;
            return false;
        }
    }

    // ------------------------------------------------------------------ engine.Triangle
    static class Triangle {
        final Node n1, n2, n3;
        int owner = -1;
        int initialOwner = -1;
        final boolean[] canCapture = {true, true};

        Triangle(Node n1, Node n2, Node n3) {
            this.n1 = n1;
            this.n2 = n2;
            this.n3 = n3;
        }

        boolean sameNodes(Triangle t) {
            return hasNode(t.n1) && hasNode(t.n2) && hasNode(t.n3);
        }

        boolean hasNode(Node n) {
            return n1 == n || n2 == n || n3 == n;
        }

        boolean hasNeighbor(Node n) {
            return n1.neighbors.contains(n) || n2.neighbors.contains(n) || n3.neighbors.contains(n);
        }

        void finalizeTurn() {
            initialOwner = owner;
        }

        boolean capture() {
            int old = owner;
            if (canCapture[0] && n1.owner() == 0 && n2.owner() == 0 && n3.owner() == 0) owner = 0;
            if (canCapture[1] && n1.owner() == 1 && n2.owner() == 1 && n3.owner() == 1) owner = 1;
            return owner != old;
        }

        void use(int unitsTaken) {
            for (Node node : new Node[]{n1, n2, n3}) {
                int take = Math.min(unitsTaken, node.units[owner]);
                node.units[owner] -= take;
                node.remainingUnits[owner] -= take;
            }
            canCapture[owner] = false;
            owner = -1;
        }

        boolean canUse(int p, int unitsNeeded) {
            return owner == p && initialOwner == p &&
                    n1.remainingUnits[p] >= unitsNeeded && n2.remainingUnits[p] >= unitsNeeded && n3.remainingUnits[p] >= unitsNeeded;
        }

        void updateAllowedCaptures() {
            for (int i = 0; i < 2; i++) {
                if (owner != -1) canCapture[i] = owner != i;
                else canCapture[i] |= n1.units[i] == 0 && n2.units[i] == 0 && n3.units[i] == 0;
            }
        }

        String input(int p) {
            int ownerId = owner == -1 ? -1 : (owner == p ? 0 : 1);
            return n1.id + " " + n2.id + " " + n3.id + " " + ownerId + " " + (canCapture[p] ? 1 : 0) + " " + (canCapture[1 - p] ? 1 : 0);
        }
    }

    // ------------------------------------------------------------------ engine.Board
    static class Board {
        final ArrayList<Node> nodes = new ArrayList<>();
        ArrayList<Triangle> triangles = new ArrayList<>();
        final int[] score = new int[2];
        final int[] unitCount = new int[2];
        final int[] triangleCount = new int[2];
        final int[] killedBySurround = new int[2];
        boolean killedSurrounded = false;

        Board(Random random) {
            ArrayList<Edge> existingEdges;
            while (true) {
                int tries = 0;
                nodes.clear();
                HashSet<Integer> nodeDistances = new HashSet<>();
                while (nodes.size() < NODE_COUNT && tries++ < 10000) {
                    Node n1 = new Node(nodes.size(), SIDE_BORDER + random.nextInt(WIDTH), TOP_BORDER + random.nextInt(HEIGHT));
                    Node n2 = n1.mirror();
                    if (nodes.stream().anyMatch(n -> n.dist(n1) < NODE_MIN_DIST) ||
                            n1.dist(n2) < NODE_MIN_DIST ||
                            nodeDistances.contains(n1.dist2(n2)) ||
                            nodes.stream().anyMatch(n -> nodeDistances.contains(n.dist2(n1)))) continue;
                    if (obtuseAngle(nodes, n1, n2)) continue;
                    nodeDistances.add(n1.dist2(n2));
                    for (Node n : nodes) nodeDistances.add(n.dist2(n1));
                    nodes.add(n1);
                    nodes.add(n2);
                }

                ArrayList<Edge> edges = new ArrayList<>();
                for (int i = 0; i < nodes.size(); i++) {
                    for (int j = i + 1; j < nodes.size(); j++) edges.add(new Edge(nodes.get(i), nodes.get(j)));
                }
                edges.sort((o1, o2) -> (int) Math.signum(o1.length() - o2.length()));
                existingEdges = new ArrayList<>();
                for (int i = 0; i < edges.size(); i++) {
                    if (edges.get(i).isBlocked(existingEdges)) {
                        edges.remove(i);
                        i--;
                    } else {
                        edges.get(i).makeNeighbors();
                        existingEdges.add(edges.get(i));
                    }
                }

                updateTriangles();
                if (isStronglyConnected() && triangles.size() >= MIN_TRIANGLE_COUNT) break;
            }

            HashSet<Edge> testedEdges = new HashSet<>();
            int removeEdgeCount = (int) (existingEdges.size() * (random.nextDouble() * (SPARSE_MAX - SPARSE_MIN) + SPARSE_MIN));
            while (removeEdgeCount > 0) {
                removeEdgeCount -= 2;
                Edge toDisconnect = existingEdges.get(random.nextInt(existingEdges.size()));
                if (testedEdges.contains(toDisconnect)) continue;
                Node mirror1 = getMirror(toDisconnect.n1);
                Node mirror2 = getMirror(toDisconnect.n2);
                Edge partner = null;
                for (Edge edge : existingEdges) {
                    if (edge.n1 == mirror1 && edge.n2 == mirror2 || edge.n1 == mirror2 && edge.n2 == mirror1) partner = edge;
                }
                testedEdges.add(toDisconnect);
                testedEdges.add(partner);

                updateTriangles();
                toDisconnect.unmakeNeighbors();
                partner.unmakeNeighbors();
                updateTriangles();
                if (triangles.size() >= MIN_TRIANGLE_COUNT && isStronglyConnected()) {
                    existingEdges.remove(toDisconnect);
                    existingEdges.remove(partner);
                } else {
                    toDisconnect.makeNeighbors();
                    partner.makeNeighbors();
                }
            }

            ArrayList<Node> nodesByX = new ArrayList<>(nodes);
            nodesByX.sort(Comparator.comparingInt(n -> n.x));
            for (int i = 0; i < 3; i++) {
                Node node = nodesByX.get(i);
                node.units[0] = 1;
                getMirror(node).units[1] = 1;
            }

            updateTriangles();
            while (finalizeTurn()) ;
        }

        boolean isStronglyConnected() {
            for (Node blocked : nodes) {
                Node start = nodes.get(0);
                if (start == blocked) start = nodes.get(1);
                int[] dist = start.bfs(blocked);
                int unreachable = 0;
                for (int i = 0; i < nodes.size(); i++) if (dist[i] == dist.length) unreachable++;
                if (unreachable > 1) return false;
            }
            return true;
        }

        boolean obtuseAngle(ArrayList<Node> nodes, Node n1, Node n2) {
            ArrayList<Node> list = new ArrayList<>(nodes);
            list.add(n1);
            list.add(n2);
            for (int i1 = 0; i1 < list.size(); i1++) {
                for (int i2 = i1 + 1; i2 < list.size(); i2++) {
                    for (int i3 = i2 + 1; i3 < list.size(); i3++) {
                        double a = list.get(i1).dist(list.get(i2));
                        double b = list.get(i1).dist(list.get(i3));
                        double c = list.get(i2).dist(list.get(i3));
                        if (a > MAX_PATH_LENGTH || b > MAX_PATH_LENGTH || c > MAX_PATH_LENGTH) continue;
                        double alpha = Math.toDegrees(Math.acos((b * b + c * c - a * a) / (2 * b * c)));
                        double beta = Math.toDegrees(Math.acos((a * a + c * c - b * b) / (2 * a * c)));
                        double gamma = Math.toDegrees(Math.acos((a * a + b * b - c * c) / (2 * a * b)));
                        if (alpha > MAX_ANGLE || beta > MAX_ANGLE || gamma > MAX_ANGLE) return true;
                    }
                }
            }
            return false;
        }

        Node getMirror(Node node) {
            if (node.id % 2 == 0) return nodes.get(node.id + 1);
            return nodes.get(node.id - 1);
        }

        void updateTriangles() {
            ArrayList<Triangle> oldTriangles = triangles;
            ArrayList<Triangle> newTriangles = findTriangles();
            triangles = new ArrayList<>();
            for (Triangle t : newTriangles) {
                Triangle old = null;
                for (Triangle t2 : oldTriangles) if (t.sameNodes(t2)) old = t2;
                triangles.add(old == null ? t : old);
            }
        }

        ArrayList<Triangle> findTriangles() {
            ArrayList<Triangle> result = new ArrayList<>();
            for (Node node1 : nodes) {
                for (Node node2 : node1.neighbors) {
                    for (Node node3 : node2.neighbors) {
                        if (node1.id < node2.id && node2.id < node3.id && node1.neighbors.contains(node3) &&
                                nodes.stream().noneMatch(n -> isInside(n, node1, node2, node3)))
                            result.add(new Triangle(node1, node2, node3));
                    }
                }
            }
            return result;
        }

        ArrayList<Edge> edges() {
            ArrayList<Edge> existingEdges = new ArrayList<>();
            for (Node n1 : nodes) {
                for (Node n2 : n1.neighbors) {
                    if (n2.id > n1.id) existingEdges.add(new Edge(n1, n2));
                }
            }
            return existingEdges;
        }

        boolean canConnect(Node from, Node to) {
            return !new Edge(from, to).isBlocked(edges());
        }

        void connect(Node from, Node to) {
            from.addNeighbor(to);
            updateTriangles();
        }

        void disconnect(Node from, Node to) {
            from.removeNeighbor(to);
            updateTriangles();
        }

        static boolean isInside(Node n, Node a, Node b, Node c) {
            if (n == a || n == b || n == c) return false;
            boolean ccw1 = Edge.ccw(n, a, b);
            boolean ccw2 = Edge.ccw(n, b, c);
            boolean ccw3 = Edge.ccw(n, c, a);
            boolean hasNeg = !ccw1 || !ccw2 || !ccw3;
            boolean hasPos = ccw1 || ccw2 || ccw3;
            return !(hasNeg && hasPos);
        }

        void applyActions(TaskManager taskManager) {
            killedSurrounded = false;
            ArrayList<ArrayList<Task>> tasks = taskManager.popTasks(this);
            ArrayList<Task> toApply = new ArrayList<>();
            for (int player = 0; player < 2; player++) {
                while (!tasks.get(player).isEmpty()) {
                    Task task = tasks.get(player).remove(0);
                    if (!task.canApply(this, true)) continue;
                    toApply.add(task);
                    if (!task.allowMultiplePerFrame()) break;
                }
            }
            toApply.sort(Comparator.comparingInt(Task::cost));
            for (Task task : toApply) {
                if (!task.canApply(this, false)) continue;
                if (task.allowMultiplePerFrame() && !task.canApply(this, true)) continue;
                task.apply(this);
            }
            taskManager.returnTasks(tasks);
            makeStats();
        }

        void makeStats() {
            for (int p = 0; p < 2; p++) {
                unitCount[p] = 0;
                triangleCount[p] = 0;
                for (Node node : nodes) unitCount[p] += node.units[p];
                for (Triangle t : triangles) if (t.owner == p) triangleCount[p]++;
            }
        }

        boolean finalizeTurn() {
            for (Node node : nodes) node.finalizeTurn();
            for (Triangle t : triangles) t.updateAllowedCaptures();
            if (surroundNodes() || captureTriangles()) {
                makeStats();
                return true;
            }
            for (Triangle t : triangles) {
                if (t.owner != -1) score[t.owner]++;
                t.finalizeTurn();
            }
            makeStats();
            return false;
        }

        boolean surroundNodes() {
            if (killedSurrounded) return false;
            killedSurrounded = true;
            boolean[][] advantage = new boolean[2][nodes.size()];
            for (int i = 0; i < 2; i++) {
                for (Node node : nodes) advantage[i][node.id] = node.units[i] > node.units[(i + 1) % 2];
            }
            boolean surrounded = false;
            for (int i = 0; i < 2; i++) {
                int opponentIndex = (i + 1) % 2;
                for (Node node : nodes) {
                    if (node.units[i] > 0 && !node.neighbors.isEmpty() && node.neighbors.stream().allMatch(n -> advantage[opponentIndex][n.id])) {
                        killedBySurround[i] += node.units[i];
                        node.units[i] = 0;
                        surrounded = true;
                    }
                }
            }
            return surrounded;
        }

        boolean captureTriangles() {
            boolean result = false;
            for (Triangle t : triangles) result |= t.capture();
            return result;
        }

        String getInput(boolean initial, int p, boolean allowNewEdges) {
            StringBuilder sb = new StringBuilder();
            if (initial) {
                sb.append(nodes.size()).append('\n');
                for (Node node : nodes) sb.append(node.id).append(' ').append(node.x).append(' ').append(node.y).append('\n');
            }
            ArrayList<String> links = new ArrayList<>();
            for (Node n1 : nodes) {
                for (Node n2 : n1.neighbors) {
                    if (n2.id <= n1.id) continue;
                    links.add(n1.id + " " + n2.id);
                }
            }
            // paths that ADD_PATH could create, from league 3 on (the edge list is built once instead of once per pair)
            ArrayList<String> canLink = new ArrayList<>();
            if (allowNewEdges) {
                ArrayList<Edge> existingEdges = edges();
                for (Node n1 : nodes) {
                    for (Node n2 : nodes) {
                        if (n1.id < n2.id && !n1.neighbors.contains(n2) && !new Edge(n1, n2).isBlocked(existingEdges)) {
                            canLink.add(n1.id + " " + n2.id);
                        }
                    }
                }
            }
            sb.append(score[p]).append(' ').append(score[1 - p]).append('\n');
            for (Node node : nodes) sb.append(node.id).append(' ').append(node.units[p]).append(' ').append(node.units[1 - p]).append('\n');
            sb.append(links.size()).append('\n');
            for (String link : links) sb.append(link).append('\n');
            sb.append(triangles.size()).append('\n');
            for (Triangle t : triangles) sb.append(t.input(p)).append('\n');
            sb.append(canLink.size()).append('\n');
            for (String link : canLink) sb.append(link).append('\n');
            return sb.toString();
        }

        Node getNode(String id, Task task) {
            int nodeId = Integer.parseInt(id);
            if (nodeId < 0 || nodeId >= nodes.size()) {
                task.failedParsing = true;
                return null;
            }
            return nodes.get(nodeId);
        }

        Triangle getTriangle(Node a, Node b, Node c, Task task) {
            if (a == null || b == null || c == null) return null;
            for (Triangle t : triangles) if (t.hasNode(a) && t.hasNode(b) && t.hasNode(c)) return t;
            task.failedParsing = true;
            return null;
        }
    }

    // ------------------------------------------------------------------ engine.task
    static abstract class Task {
        final int player;
        boolean failedParsing;

        Task(int player) {
            this.player = player;
        }

        abstract boolean allowMultiplePerFrame();

        abstract int priority();

        abstract int requiredLeague();

        int cost() {
            return 0;
        }

        abstract boolean canApply(Board board, boolean strict);

        abstract void apply(Board board);
    }

    static final Pattern MOVE = Pattern.compile("^\\s*(?<action>MOVE)\\s+(?<from>\\d+)\\s+(?<to>\\d+)\\s+(?<amount>-?\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern SPAWN = Pattern.compile("^\\s*(?<action>SPAWN)\\s+(?<spawn>\\d+)\\s+(?<node2>\\d+)\\s+(?<node3>\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern ATTACK = Pattern.compile("^\\s*(?<action>ATTACK)\\s+(?<node1>\\d+)\\s+(?<node2>\\d+)\\s+(?<node3>\\d+)\\s+(?<target>\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern ADD_PATH = Pattern.compile("^\\s*(?<action>ADD_PATH)\\s+(?<nodeFrom>\\d+)\\s+(?<node2>\\d+)\\s+(?<node3>\\d+)\\s+(?<nodeTo>\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern REMOVE_PATH = Pattern.compile("^\\s*(?<action>REMOVE_PATH)\\s+(?<nodeFrom>\\d+)\\s+(?<node2>\\d+)\\s+(?<node3>\\d+)\\s+(?<nodeTo>\\d+)\\s*$", Pattern.CASE_INSENSITIVE);
    static final Pattern[] DEBUG = {
            Pattern.compile("^\\s*(?<action>CIRCLE)\\s+(?<x>-?\\d+)\\s+(?<y>-?\\d+)\\s+(?<r>-?\\d+)\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(?<action>LINE)\\s+(?<x1>-?\\d+)\\s+(?<y1>-?\\d+)\\s+(?<x2>-?\\d+)\\s+(?<y2>-?\\d+)\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(?<action>RECT)\\s+(?<x>-?\\d+)\\s+(?<y>-?\\d+)\\s+(?<w>-?\\d+)\\s+(?<h>-?\\d+)\\s*$", Pattern.CASE_INSENSITIVE),
            Pattern.compile("^\\s*(?<action>TEXT)\\s+(?<x>-?\\d+)\\s+(?<y>-?\\d+)\\s+(?<text>.+)\\s*$", Pattern.CASE_INSENSITIVE)};

    static class MoveTask extends Task {
        Node from, to;
        int amount;

        MoveTask(int player, Board board, Matcher m) {
            super(player);
            from = board.getNode(m.group("from"), this);
            to = board.getNode(m.group("to"), this);
            amount = Integer.parseInt(m.group("amount"));
            if (amount <= 0) failedParsing = true;
        }

        boolean allowMultiplePerFrame() { return true; }

        int priority() { return 1; }

        int requiredLeague() { return 1; }

        boolean canApply(Board board, boolean strict) {
            if (!strict) return true;
            return !failedParsing && from.canMoveTo(player, to);
        }

        void apply(Board board) {
            from.moveTo(player, to, Math.min(amount, from.remainingUnits[player]));
        }
    }

    static class SpawnTask extends Task {
        Node spawn;
        Triangle triangle;

        SpawnTask(int player, Board board, Matcher m) {
            super(player);
            spawn = board.getNode(m.group("spawn"), this);
            Node node2 = board.getNode(m.group("node2"), this);
            Node node3 = board.getNode(m.group("node3"), this);
            triangle = board.getTriangle(spawn, node2, node3, this);
        }

        boolean allowMultiplePerFrame() { return true; }

        int priority() { return 2; }

        int requiredLeague() { return 1; }

        boolean canApply(Board board, boolean strict) {
            return !failedParsing && triangle.canUse(player, 0);
        }

        void apply(Board board) {
            triangle.use(0);
            spawn.units[player]++;
        }
    }

    static class AttackTask extends Task {
        Node target;
        Triangle triangle;

        AttackTask(int player, Board board, Matcher m) {
            super(player);
            Node node1 = board.getNode(m.group("node1"), this);
            Node node2 = board.getNode(m.group("node2"), this);
            Node node3 = board.getNode(m.group("node3"), this);
            target = board.getNode(m.group("target"), this);
            triangle = board.getTriangle(node1, node2, node3, this);
            if (!failedParsing && !triangle.hasNeighbor(target)) failedParsing = true;
        }

        boolean allowMultiplePerFrame() { return false; }

        int priority() { return 0; }

        int requiredLeague() { return 2; }

        boolean canApply(Board board, boolean strict) {
            if (!strict) return true;
            return !failedParsing && triangle.canUse(player, 1) && triangle.hasNeighbor(target);
        }

        void apply(Board board) {
            triangle.use(1);
            target.units[1 - player] = 0;
            target.remainingUnits[1 - player] = 0;
        }
    }

    static class AddEdgeTask extends Task {
        Node from, to;
        Triangle triangle;

        AddEdgeTask(int player, Board board, Matcher m) {
            super(player);
            from = board.getNode(m.group("nodeFrom"), this);
            Node node2 = board.getNode(m.group("node2"), this);
            Node node3 = board.getNode(m.group("node3"), this);
            to = board.getNode(m.group("nodeTo"), this);
            triangle = board.getTriangle(from, node2, node3, this);
            if (!failedParsing && !board.canConnect(from, to)) failedParsing = true;
        }

        boolean allowMultiplePerFrame() { return false; }

        int priority() { return 3; }

        int requiredLeague() { return 3; }

        int cost() {
            return from == null || to == null ? 0 : from.dist2(to);
        }

        boolean canApply(Board board, boolean strict) {
            // the referee would crash on an invalid house id here
            if (from == null || to == null) return false;
            boolean valid = !failedParsing && triangle.canUse(player, 1) && !from.neighbors.contains(to);
            if (!strict) valid = true;
            valid &= board.canConnect(from, to);
            return valid;
        }

        void apply(Board board) {
            triangle.use(1);
            board.connect(from, to);
        }
    }

    static class RemoveEdgeTask extends Task {
        Node from, to;
        Triangle triangle;

        RemoveEdgeTask(int player, Board board, Matcher m) {
            super(player);
            from = board.getNode(m.group("nodeFrom"), this);
            Node node2 = board.getNode(m.group("node2"), this);
            Node node3 = board.getNode(m.group("node3"), this);
            to = board.getNode(m.group("nodeTo"), this);
            triangle = board.getTriangle(from, node2, node3, this);
            if (!failedParsing && !triangle.hasNeighbor(to)) failedParsing = true;
        }

        boolean allowMultiplePerFrame() { return false; }

        int priority() { return 4; }

        int requiredLeague() { return 3; }

        boolean canApply(Board board, boolean strict) {
            if (!strict) return true;
            return !failedParsing && triangle.canUse(player, 1) && from.neighbors.contains(to) && !triangle.hasNode(to);
        }

        void apply(Board board) {
            triangle.use(1);
            board.disconnect(from, to);
        }
    }

    static class TaskManager {
        final ArrayList<Task> tasks = new ArrayList<>();

        /** @return false when the command is unknown (critical error: the player loses) */
        boolean parseTasks(int player, Board board, String command, int league) {
            for (String comm : command.split(";")) {
                if (comm.toUpperCase().startsWith("MSG ")) continue;
                if (comm.trim().isEmpty() || comm.trim().equalsIgnoreCase("WAIT")) continue;
                Task task = null;
                try {
                    Matcher m;
                    if ((m = MOVE.matcher(comm)).matches()) task = new MoveTask(player, board, m);
                    else if ((m = SPAWN.matcher(comm)).matches()) task = new SpawnTask(player, board, m);
                    else if ((m = ATTACK.matcher(comm)).matches()) task = new AttackTask(player, board, m);
                    else if ((m = ADD_PATH.matcher(comm)).matches()) task = new AddEdgeTask(player, board, m);
                    else if ((m = REMOVE_PATH.matcher(comm)).matches()) task = new RemoveEdgeTask(player, board, m);
                    else {
                        boolean debug = false;
                        for (Pattern p : DEBUG) debug |= p.matcher(comm).matches();
                        if (debug) continue;
                    }
                } catch (Exception ex) {
                    return false;
                }
                if (task == null) return false;
                if (task.requiredLeague() > league) continue;
                if (!task.canApply(board, true)) task.failedParsing = true;
                tasks.add(task);
            }
            return true;
        }

        boolean hasTasks(Board board) {
            return !peekTasks(board).isEmpty();
        }

        ArrayList<ArrayList<Task>> popTasks(Board board) {
            ArrayList<Task> pop = peekTasks(board);
            tasks.removeAll(pop);
            ArrayList<ArrayList<Task>> result = new ArrayList<>();
            result.add(new ArrayList<>());
            result.add(new ArrayList<>());
            for (Task task : pop) result.get(task.player).add(task);
            return result;
        }

        private ArrayList<Task> peekTasks(Board board) {
            for (int i = tasks.size() - 1; i >= 0; i--) {
                if (!tasks.get(i).canApply(board, true)) tasks.remove(i);
            }
            ArrayList<Task> result = new ArrayList<>();
            for (Task task : tasks) {
                if (!result.isEmpty() && result.get(0).priority() > task.priority()) result.clear();
                if (result.isEmpty() || task.priority() == result.get(0).priority()) result.add(task);
            }
            result.sort(Comparator.comparingInt(Task::cost));
            return result;
        }

        void returnTasks(ArrayList<ArrayList<Task>> remaining) {
            for (ArrayList<Task> playerTasks : remaining) tasks.addAll(playerTasks);
        }
    }

    // ------------------------------------------------------------------ bot processes
    static class Bot {
        final Process process;
        final BufferedWriter stdin;
        final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
        long maxMillis, totalMillis, firstMillis;
        int turns;

        Bot(String[] command, File errorLog) throws IOException {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectError(ProcessBuilder.Redirect.to(errorLog));
            process = pb.start();
            stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()));
            BufferedReader stdout = new BufferedReader(new InputStreamReader(process.getInputStream()));
            Thread reader = new Thread(() -> {
                try {
                    String line;
                    while ((line = stdout.readLine()) != null) lines.add(line);
                } catch (IOException ignored) {
                }
                lines.add("\u0000EOF");
            });
            reader.setDaemon(true);
            reader.start();
        }

        void send(String input) throws IOException {
            stdin.write(input);
            stdin.flush();
        }

        String receive(long sentAt, long timeoutMillis) throws InterruptedException {
            String line = lines.poll(timeoutMillis, TimeUnit.MILLISECONDS);
            long elapsed = (System.nanoTime() - sentAt) / 1_000_000;
            if (turns == 0) firstMillis = elapsed;
            else {
                maxMillis = Math.max(maxMillis, elapsed);
                totalMillis += elapsed;
            }
            turns++;
            if (line == null || line.equals("\u0000EOF")) return null;
            return line;
        }

        void kill() {
            process.destroyForcibly();
        }
    }

    static class Result {
        long seed;
        int[] score = new int[2];
        int[] triangles = new int[2];
        int[] units = new int[2];
        int[] surroundKills = new int[2];
        String reason = "turns";
        int decisions;
        int totalTriangles;
        long[] maxMillis = new long[2];
        long[] avgMillis = new long[2];
        long[] firstMillis = new long[2];
    }

    static boolean trace = false;
    static File logDir;

    static Result play(long seed, String[][] commands, int league) throws Exception {
        Board board = new Board(new Random(seed));
        Bot[] bots = new Bot[2];
        Result result = new Result();
        result.seed = seed;
        result.totalTriangles = board.triangles.size();
        TaskManager taskManager = new TaskManager();
        boolean[] active = {true, true};
        boolean ended = false;
        try {
            for (int p = 0; p < 2; p++) {
                // stderr of each bot is kept for debugging: <build>/logs/<seed>_<command hash>_p<index>.err
                String log = seed + "_" + Integer.toHexString(Arrays.hashCode(commands[p])) + "_p" + p + ".err";
                bots[p] = new Bot(commands[p], new File(logDir, log));
            }
            int maxTurns = 200;
            for (int turn = 1; turn <= maxTurns && !ended; turn++) {
                if (!taskManager.hasTasks(board)) {
                    if (board.finalizeTurn()) {
                        maxTurns++;
                        continue;
                    }
                    long[] sentAt = new long[2];
                    for (int p = 0; p < 2; p++) {
                        if (board.unitCount[p] == 0 && board.triangleCount[p] == 0) {
                            active[p] = false;
                            result.reason = "P" + p + " no units or triangles left";
                        } else if (board.unitCount[p] == 1 && board.triangleCount[p] == 0 && board.score[p] < board.score[1 - p]) {
                            active[p] = false;
                            result.reason = "P" + p + " can't score any more points";
                        } else if (board.triangleCount[1 - p] >= board.triangles.size() * 0.8) {
                            active[p] = false;
                            result.reason = "P" + p + " is dominated";
                        }
                        if (!active[p]) {
                            ended = true;
                            continue;
                        }
                        sentAt[p] = System.nanoTime();
                        try {
                            bots[p].send(board.getInput(turn == 1, p, league >= 3));
                        } catch (IOException e) {
                            active[p] = false;
                            ended = true;
                            result.reason = "P" + p + " crashed (closed stdin)";
                        }
                    }
                    if (ended) break;
                    result.decisions++;
                    if (trace) {
                        System.out.printf("T%3d score %4d-%4d tri %2d-%2d/%d units %2d-%2d%n", result.decisions, board.score[0], board.score[1],
                                board.triangleCount[0], board.triangleCount[1], board.triangles.size(), board.unitCount[0], board.unitCount[1]);
                    }
                    for (int p = 0; p < 2; p++) {
                        String output = bots[p].receive(sentAt[p], turn == 1 ? 10000 : 2000);
                        if (trace && output != null) System.out.println("   P" + p + ": " + output.replaceAll(";(CIRCLE|LINE)[^;]*", ""));
                        if (output == null) {
                            active[p] = false;
                            ended = true;
                            result.reason = "P" + p + " timeout/crash";
                            continue;
                        }
                        if (!taskManager.parseTasks(p, board, output, league)) {
                            active[p] = false;
                            ended = true;
                            result.reason = "P" + p + " invalid command: " + output;
                        }
                    }
                    if (ended) break;
                } else {
                    maxTurns++;
                }
                board.applyActions(taskManager);
            }
        } finally {
            for (Bot bot : bots) if (bot != null) bot.kill();
        }
        for (int p = 0; p < 2; p++) {
            result.score[p] = active[p] ? board.score[p] : -1;
            result.triangles[p] = board.triangleCount[p];
            result.units[p] = board.unitCount[p];
            result.surroundKills[p] = board.killedBySurround[p];
            result.maxMillis[p] = bots[p].maxMillis;
            result.firstMillis[p] = bots[p].firstMillis;
            result.avgMillis[p] = bots[p].turns > 1 ? bots[p].totalMillis / (bots[p].turns - 1) : 0;
        }
        return result;
    }

    // a .java file is compiled into the build directory, boss1/boss2/boss3 run the boss ports, anything else is a command line
    static String[] resolveBot(String spec, File build, File bossSource) throws IOException {
        String java = new File(System.getProperty("java.home"), "bin/java").getPath();
        if (spec.matches("boss[123]")) {
            File classes = compile(bossSource, new File(build, "boss"));
            return new String[]{java, "-cp", classes.getPath(), "Boss", spec.substring(4)};
        }
        if (spec.endsWith(".java")) {
            File source = new File(spec);
            File classes = compile(source, new File(build, "bots/" + spec.replaceAll("\\W", "_")));
            return new String[]{java, "-cp", classes.getPath(), source.getName().replace(".java", "")};
        }
        return spec.trim().split("\\s+");
    }

    static File compile(File source, File classes) throws IOException {
        if (!source.isFile()) throw new FileNotFoundException(source.getPath());
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("a JDK is needed to compile " + source);
        classes.mkdirs();
        if (compiler.run(null, null, null, "-nowarn", "-d", classes.getPath(), source.getPath()) != 0) {
            throw new IllegalStateException("compilation failed: " + source);
        }
        return classes;
    }

    public static void main(String[] args) throws Exception {
        String botA = null, botB = null;
        int games = 20, threads = 8, league = 1;
        long firstSeed = 1;
        boolean swap = false, verbose = false;
        File build = new File("tools/arena/build");
        File bossSource = new File("tools/arena/Boss.java");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-a": botA = args[++i]; break;
                case "-b": botB = args[++i]; break;
                case "-n": games = Integer.parseInt(args[++i]); break;
                case "-t": threads = Integer.parseInt(args[++i]); break;
                case "-s": firstSeed = Long.parseLong(args[++i]); break;
                case "-l": league = Integer.parseInt(args[++i]); break;
                case "-swap": swap = true; break;
                case "-v": verbose = true; break;
                case "-trace": trace = true; break;
                case "-build": build = new File(args[++i]); break;
                case "-boss": bossSource = new File(args[++i]); break;
                default: throw new IllegalArgumentException(args[i]);
            }
        }
        if (botA == null || botB == null) {
            System.out.println("Usage: java tools/arena/Arena.java -a Player.java -b boss1 [-n 50] [-swap] [-v]");
            return;
        }
        logDir = new File(build, "logs");
        logDir.mkdirs();
        final String[] a = resolveBot(botA, build, bossSource), b = resolveBot(botB, build, bossSource);
        System.out.println("A = " + botA + ", B = " + botB);
        final int lg = league;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<Result>> futures = new ArrayList<>();
        List<Boolean> aIsP0 = new ArrayList<>();
        for (int g = 0; g < games; g++) {
            long seed = firstSeed + g;
            futures.add(pool.submit(() -> play(seed, new String[][]{a, b}, lg)));
            aIsP0.add(true);
            if (swap) {
                futures.add(pool.submit(() -> play(seed, new String[][]{b, a}, lg)));
                aIsP0.add(false);
            }
        }
        int wins = 0, losses = 0, draws = 0;
        long scoreA = 0, scoreB = 0, maxMillisA = 0, firstMillisA = 0;
        double avgMillisA = 0;
        Map<String, Integer> reasons = new TreeMap<>();
        for (int i = 0; i < futures.size(); i++) {
            Result r = futures.get(i).get();
            int pa = aIsP0.get(i) ? 0 : 1, pb = 1 - pa;
            String outcome;
            if (r.score[pa] > r.score[pb]) { wins++; outcome = "WIN "; }
            else if (r.score[pa] < r.score[pb]) { losses++; outcome = "LOSS"; }
            else { draws++; outcome = "DRAW"; }
            scoreA += Math.max(0, r.score[pa]);
            scoreB += Math.max(0, r.score[pb]);
            maxMillisA = Math.max(maxMillisA, r.maxMillis[pa]);
            firstMillisA = Math.max(firstMillisA, r.firstMillis[pa]);
            avgMillisA += r.avgMillis[pa];
            String reason = r.reason.replace("P" + pa, "A").replace("P" + pb, "B");
            if (reason.startsWith("A invalid") || reason.startsWith("B invalid")) reason = reason.substring(0, 9);
            reasons.merge(reason, 1, Integer::sum);
            if (verbose) {
                System.out.printf("seed %4d A=P%d %s  A %4d - %4d B  tri %2d/%2d-%2d  units %2d-%2d  surroundKills A%d B%d  decisions %d  %s%n",
                        r.seed, pa, outcome, r.score[pa], r.score[pb], r.triangles[pa], r.totalTriangles, r.triangles[pb],
                        r.units[pa], r.units[pb], r.surroundKills[pa], r.surroundKills[pb], r.decisions, reason);
            }
        }
        pool.shutdown();
        int total = futures.size();
        System.out.printf("A vs B over %d games: %d W / %d L / %d D  (win rate %.1f%%)  avg score A %.1f  B %.1f%n",
                total, wins, losses, draws, 100.0 * (wins + 0.5 * draws) / total, (double) scoreA / total, (double) scoreB / total);
        System.out.printf("A timing: first turn max %d ms, other turns max %d ms, avg %.1f ms%n", firstMillisA, maxMillisA, avgMillisA / total);
        System.out.println("End reasons: " + reasons);
    }
}
