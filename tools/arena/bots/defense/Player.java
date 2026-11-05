import java.util.*;
import java.io.*;
import java.math.*;

/**
 * TryAngle Catch bot.
 *
 * Referee facts the strategy relies on:
 * - a captured triangle stays owned until the opponent captures it or it is used, even with empty corners;
 * - a used triangle can only be captured again by us once none of our units stands on its corners;
 * - units die when every neighbor house has a strict opponent majority.
 *
 * Each turn: ATTACK big opponent stacks, defend owned triangles the opponent could complete, kill opponent
 * stacks that can be surrounded, greedily send free units to the most profitable captures, vacate used
 * triangles so they can be recaptured, and spend triangles on SPAWN: the bigger army wins.
 * ATTACK needs league 2 (Wood 1) or above.
 * Units do not avoid houses the opponent could surround: holding them back costs more captures than it saves.
 * Idle units push towards the center and the opponent side; once the opening is over and the army is big
 * enough, a third of it goes back to capture the triangles left near our start.
 **/
class Player {

    static final int ME = 0;
    static final int OPP = 1;
    static final int NONE = -1;
    static final int INF = 1_000_000;
    static final int MAX_TURNS = 200;
    // a spawned unit needs some turns to pay back the income of the triangle it costs
    static final int SPAWN_MIN_TURNS_LEFT = 20;
    static final int MAX_SPAWNS_PER_TURN = 6;
    // spawn while the opponent army is not outnumbered by this margin
    static final int ARMY_MARGIN = 2;
    // corners guarded by bigger stacks are left alone
    static final int MAX_UNITS_PER_CORNER = 8;
    // surround checks enumerate subsets of neighbors
    static final int MAX_HALL_TARGETS = 10;
    // stealing a triangle also removes the opponent's income
    static final double OPP_TRIANGLE_VALUE = 2;
    // an attack costs a triangle and 3 units: only worth it against big stacks
    static final int ATTACK_MIN_KILLS = 4;
    // map zones: a triangle is central when its corners are about as far from both starts (in moves)
    static final double CENTER_WIDTH = 2;
    // the opening ends once we own this share of the central triangles, or after this turn
    static final double CENTER_SHARE = 0.5;
    static final int INVASION_MAX_TURN = 40;
    // after the opening, this share of the army takes the triangles left near our start, once big enough
    static final double REAR_SHARE = 1.0 / 3;
    static final int MIN_SPLIT_ARMY = 20;

    static int houseCount;
    static int[] my, opp;
    static int[][] neighbors;   // sorted by id, like the referee
    static int[][] dist;
    static int[][] byDistance;  // byDistance[h] = all houses sorted by distance from h
    static List<Triangle> triangles = new ArrayList<>();
    static int turn;
    static int turnsLeft;
    static int[] startMine, startTheirs; // houses holding units on the first turn
    static int[] front;                  // moves from our start minus moves from the opponent start
    static boolean invasionDone;
    static int[] rearLeft;               // free units of the rear group, null while the army is not split

    // per-turn plan
    static int[] free;              // units not assigned to any task yet
    static int[] stay;              // units assigned to stay on their house
    static int[] arrivals;          // units entering the house this turn
    static int[] destined;          // units whose final destination is the house
    static int[] spawned;           // units spawned on the house this turn
    static int[][] moves;           // moves[from][to] = amount
    static boolean[] noEntry;       // corners of used triangles being vacated this turn
    static List<Triangle> remainingTargets;
    static List<String> attacks;
    static List<String> spawns;
    static Set<Triangle> usedTriangles;

    // scratch buffers of oppCanTakeAll
    static int[] sourceHouse, sourceMask, sourceStamp;
    static int stamp;

    static class Triangle {
        final int[] corners;
        final int owner;
        final boolean meCanCapture;
        final boolean opponentCanCapture;

        Triangle(int house1, int house2, int house3, int owner, boolean meCanCapture, boolean opponentCanCapture) {
            this.corners = new int[]{house1, house2, house3};
            this.owner = owner;
            this.meCanCapture = meCanCapture;
            this.opponentCanCapture = opponentCanCapture;
        }

        // used by us: it can only be captured again once our units left all its corners
        boolean blocked() {
            return owner == NONE && !meCanCapture;
        }

        boolean capturable() {
            return owner != ME && meCanCapture;
        }

        boolean has(int h) {
            return corners[0] == h || corners[1] == h || corners[2] == h;
        }

        String spawnCommand(int spawnHouse) {
            StringBuilder sb = new StringBuilder("SPAWN ").append(spawnHouse);
            for (int h : corners) if (h != spawnHouse) sb.append(' ').append(h);
            return sb.toString();
        }
    }

    static class Plan {
        Triangle triangle;
        List<int[]> pulls = new ArrayList<>(); // {from, to, amount}
        int eta;
        double score;
    }

    public static void main(String args[]) {
        Scanner in = new Scanner(System.in);
        houseCount = in.nextInt();
        for (int i = 0; i < houseCount; i++) {
            int houseId = in.nextInt();
            int houseX = in.nextInt();
            int houseY = in.nextInt();
        }
        my = new int[houseCount];
        opp = new int[houseCount];
        sourceHouse = new int[houseCount];
        sourceMask = new int[houseCount];
        sourceStamp = new int[houseCount];

        turn = 0;
        // game loop
        while (true) {
            turn++;
            int myScore = in.nextInt();
            int opponentScore = in.nextInt();
            for (int i = 0; i < houseCount; i++) {
                int houseId = in.nextInt();
                my[houseId] = in.nextInt();
                opp[houseId] = in.nextInt();
            }
            if (turn == 1) {
                startMine = housesWithUnits(my);
                startTheirs = housesWithUnits(opp);
            }
            List<List<Integer>> adjacency = new ArrayList<>();
            for (int i = 0; i < houseCount; i++) adjacency.add(new ArrayList<>());
            int pathCount = in.nextInt();
            for (int i = 0; i < pathCount; i++) {
                int house1 = in.nextInt();
                int house2 = in.nextInt();
                if (!adjacency.get(house1).contains(house2)) {
                    adjacency.get(house1).add(house2);
                    adjacency.get(house2).add(house1);
                }
            }
            neighbors = new int[houseCount][];
            for (int i = 0; i < houseCount; i++) {
                // the referee walks neighbors by increasing id when moving along a shortest path
                List<Integer> list = adjacency.get(i);
                Collections.sort(list);
                neighbors[i] = new int[list.size()];
                for (int k = 0; k < list.size(); k++) neighbors[i][k] = list.get(k);
            }
            triangles.clear();
            int triangleCount = in.nextInt();
            for (int i = 0; i < triangleCount; i++) {
                int house1 = in.nextInt();
                int house2 = in.nextInt();
                int house3 = in.nextInt();
                int owner = in.nextInt();
                boolean meCanCapture = in.nextInt() != 0;
                boolean opponentCanCapture = in.nextInt() != 0;
                triangles.add(new Triangle(house1, house2, house3, owner, meCanCapture, opponentCanCapture));
            }
            int linkableCount = in.nextInt();
            for (int i = 0; i < linkableCount; i++) {
                int house1 = in.nextInt();
                int house2 = in.nextInt();
            }

            computeDistances();
            turnsLeft = MAX_TURNS - turn + 1;
            System.out.println(play());
        }
    }

    static String play() {
        int n = houseCount;
        stay = new int[n];
        arrivals = new int[n];
        destined = new int[n];
        spawned = new int[n];
        moves = new int[n][n];
        noEntry = new boolean[n];
        attacks = new ArrayList<>();
        spawns = new ArrayList<>();
        usedTriangles = new HashSet<>();
        rearLeft = null;
        computeZones();

        attackStacks();
        free = my.clone();
        defendTriangles();
        surroundOpponents();
        captureTriangles();
        vacateUsedTriangles();
        moveIdleUnits();
        growArmy();

        List<String> commands = new ArrayList<>(attacks);
        for (int s = 0; s < n; s++) {
            for (int d = 0; d < n; d++) {
                if (moves[s][d] > 0) commands.add("MOVE " + s + " " + d + " " + moves[s][d]);
            }
        }
        commands.addAll(spawns);
        return commands.isEmpty() ? "WAIT" : String.join(";", commands);
    }

    /**
     * 0. attacks are applied before moves: a triangle wipes out the opponent stack on an adjacent house,
     * at the cost of the triangle and one unit on each of its corners. My and opp are updated accordingly.
     */
    static void attackStacks() {
        if (turnsLeft <= 1) return;
        while (true) {
            int target = -1;
            Triangle source = null;
            for (int h = 0; h < houseCount; h++) {
                if (opp[h] < ATTACK_MIN_KILLS || (target >= 0 && opp[h] <= opp[target])) continue;
                Triangle t = attackSource(h);
                if (t != null) {
                    target = h;
                    source = t;
                }
            }
            if (target < 0) return;
            StringBuilder sb = new StringBuilder("ATTACK");
            for (int c : source.corners) {
                sb.append(' ').append(c);
                my[c]--;
            }
            attacks.add(sb.append(' ').append(target).toString());
            usedTriangles.add(source);
            opp[target] = 0;
        }
    }

    // owned triangle next to the target with a unit on every corner, keeping the most units on its corners
    static Triangle attackSource(int target) {
        Triangle best = null;
        int bestUnits = -1;
        for (Triangle t : triangles) {
            if (t.owner != ME || usedTriangles.contains(t)) continue;
            boolean adjacent = false;
            int units = INF;
            for (int c : t.corners) {
                units = Math.min(units, my[c]);
                for (int nb : neighbors[c]) adjacent |= nb == target;
            }
            if (adjacent && units >= 1 && units > bestUnits) {
                best = t;
                bestUnits = units;
            }
        }
        return best;
    }

    // 1. keep owned triangles out of reach: the opponent must not be able to own all three corners next turn
    static void defendTriangles() {
        int[] planned = planned();
        List<Triangle> threatened = new ArrayList<>();
        for (Triangle t : triangles) {
            if (t.owner == ME && !usedTriangles.contains(t) && oppCanTakeAll(t.corners, planned)) threatened.add(t);
        }
        while (true) {
            Triangle bestTriangle = null;
            int bestCorner = -1;
            int bestAmount = INF;
            for (Iterator<Triangle> it = threatened.iterator(); it.hasNext(); ) {
                Triangle t = it.next();
                if (!oppCanTakeAll(t.corners, planned)) {
                    it.remove();
                    continue;
                }
                for (int c : t.corners) {
                    int available = free[c];
                    for (int nb : neighbors[c]) available += free[nb];
                    available = Math.min(available, MAX_UNITS_PER_CORNER);
                    for (int k = 1; k <= available && k < bestAmount; k++) {
                        planned[c] += k;
                        boolean safe = !oppCanTakeAll(t.corners, planned);
                        planned[c] -= k;
                        if (safe) {
                            bestTriangle = t;
                            bestCorner = c;
                            bestAmount = k;
                            break;
                        }
                    }
                }
            }
            if (bestTriangle == null) break;
            // units already on the corner stay, the rest steps in from the neighbors with most free units
            int missing = bestAmount;
            int take = Math.min(missing, free[bestCorner]);
            assign(bestCorner, bestCorner, take);
            missing -= take;
            while (missing > 0) {
                int src = -1;
                for (int nb : neighbors[bestCorner]) {
                    if (free[nb] > 0 && (src < 0 || free[nb] > free[src])) src = nb;
                }
                take = Math.min(missing, free[src]);
                assign(src, bestCorner, take);
                missing -= take;
            }
            planned = planned();
            threatened.remove(bestTriangle);
        }

        // triangles we cannot hold: turn them into a unit before the opponent takes them
        if (turnsLeft <= 1) return;
        for (Triangle t : threatened) {
            boolean engaged = false;
            for (int c : t.corners) engaged |= ownerOf(c) == OPP;
            if (!engaged) continue;
            int spawnCorner = -1;
            for (int c : t.corners) {
                planned[c]++;
                boolean safe = !oppCanTakeAll(t.corners, planned);
                planned[c]--;
                if (safe) {
                    spawnCorner = c;
                    break;
                }
            }
            if (spawnCorner < 0) {
                for (int c : t.corners) {
                    if (spawnCorner < 0 || planned[c] > planned[spawnCorner]) spawnCorner = c;
                }
            }
            spawn(t, spawnCorner);
            planned[spawnCorner]++;
        }
    }

    // 2. kill opponent stacks: a strict majority on every neighbor of their house kills them whatever their size
    static void surroundOpponents() {
        List<Integer> stacks = new ArrayList<>();
        for (int h = 0; h < houseCount; h++) if (opp[h] > 0) stacks.add(h);
        stacks.sort((a, b) -> opp[b] - opp[a]);
        for (int h : stacks) {
            int[] planned = planned();
            int[] demand = new int[neighbors[h].length];
            int total = 0;
            for (int i = 0; i < demand.length; i++) {
                int n = neighbors[h][i];
                demand[i] = Math.max(0, opp[n] + 1 - planned[n]);
                total += demand[i];
            }
            if (total == 0) continue;
            List<int[]> pulls = supplyDemands(neighbors[h], demand, total);
            if (pulls == null) continue;
            for (int[] pull : pulls) assign(pull[0], pull[1], pull[2]);
        }
    }

    /**
     * Free units covering the demand of every sink this turn, each unit moving at most one house
     * (max flow from free stacks to the sinks), or null when the demand cannot be met.
     */
    static List<int[]> supplyDemands(int[] sinks, int[] demand, int total) {
        int m = sinks.length;
        stamp++;
        int[] sources = new int[houseCount];
        int sourceCount = 0;
        for (int sink : sinks) {
            for (int k = -1; k < neighbors[sink].length; k++) {
                int s = k < 0 ? sink : neighbors[sink][k];
                if (free[s] > 0 && sourceStamp[s] != stamp) {
                    sourceStamp[s] = stamp;
                    sources[sourceCount++] = s;
                }
            }
        }
        // nodes: 0 = start, sources, sinks, end
        int size = sourceCount + m + 2, start = 0, end = size - 1;
        int[][] residual = new int[size][size];
        for (int i = 0; i < sourceCount; i++) {
            residual[start][1 + i] = free[sources[i]];
            for (int j = 0; j < m; j++) {
                if (dist[sources[i]][sinks[j]] <= 1) residual[1 + i][1 + sourceCount + j] = INF;
            }
        }
        for (int j = 0; j < m; j++) residual[1 + sourceCount + j][end] = demand[j];
        int flow = 0;
        int[] parent = new int[size];
        int[] queue = new int[size];
        while (flow < total) {
            Arrays.fill(parent, -1);
            parent[start] = start;
            int head = 0, tail = 0;
            queue[tail++] = start;
            while (head < tail && parent[end] < 0) {
                int u = queue[head++];
                for (int v = 0; v < size; v++) {
                    if (parent[v] < 0 && residual[u][v] > 0) {
                        parent[v] = u;
                        queue[tail++] = v;
                    }
                }
            }
            if (parent[end] < 0) return null;
            int push = INF;
            for (int v = end; v != start; v = parent[v]) push = Math.min(push, residual[parent[v]][v]);
            for (int v = end; v != start; v = parent[v]) {
                residual[parent[v]][v] -= push;
                residual[v][parent[v]] += push;
            }
            flow += push;
        }
        List<int[]> pulls = new ArrayList<>();
        for (int i = 0; i < sourceCount; i++) {
            for (int j = 0; j < m; j++) {
                int used = residual[1 + sourceCount + j][1 + i];
                if (dist[sources[i]][sinks[j]] <= 1 && used > 0) pulls.add(new int[]{sources[i], sinks[j], used});
            }
        }
        return pulls;
    }

    /**
     * 3. greedily assign free units to the most profitable capturable triangles.
     * Once the center is taken and the army is big enough, the third of the army closest to our start captures
     * the triangles left behind near our start while the rest keeps fighting in the center and beyond.
     */
    static void captureTriangles() {
        List<Triangle> rearTargets = new ArrayList<>();
        List<Triangle> otherTargets = new ArrayList<>();
        for (Triangle t : triangles) {
            if (!t.capturable()) continue;
            boolean trap = false;
            for (int c : t.corners) trap |= surroundedBy(c, opp, my);
            if (trap) continue;
            if (isRear(t)) rearTargets.add(t);
            else otherTargets.add(t);
        }
        if (invasionDone && !rearTargets.isEmpty() && total(my) >= MIN_SPLIT_ARMY) {
            rearLeft = rearGroup();
            int[] frontGroup = new int[houseCount];
            for (int h = 0; h < houseCount; h++) frontGroup[h] = free[h] - rearLeft[h];
            planCaptures(rearTargets, rearLeft);
            planCaptures(otherTargets, frontGroup);
        } else {
            rearTargets.addAll(otherTargets);
            otherTargets.clear();
            planCaptures(rearTargets, free.clone());
        }
        remainingTargets = rearTargets;
        remainingTargets.addAll(otherTargets);
    }

    // plans captures of the given targets with the given units, removes the planned targets from the list
    static void planCaptures(List<Triangle> targets, int[] supply) {
        while (!targets.isEmpty()) {
            Plan best = null;
            for (Triangle t : targets) {
                Plan plan = evaluate(t, supply);
                if (plan != null && (best == null || plan.score > best.score)) best = plan;
            }
            if (best == null) break;
            for (int[] pull : best.pulls) {
                assign(pull[0], pull[1], pull[2]);
                supply[pull[0]] -= pull[2];
            }
            targets.remove(best.triangle);
        }
    }

    // the units deepest on our side, a third of the army
    static int[] rearGroup() {
        int quota = (int) Math.round(total(my) * REAR_SHARE);
        Integer[] order = new Integer[houseCount];
        for (int h = 0; h < houseCount; h++) order[h] = h;
        Arrays.sort(order, Comparator.comparingInt(h -> front[h]));
        int[] group = new int[houseCount];
        for (int h : order) {
            if (quota <= 0) break;
            group[h] = Math.min(quota, free[h]);
            quota -= group[h];
        }
        return group;
    }

    static Plan evaluate(Triangle t, int[] units) {
        Plan plan = new Plan();
        plan.triangle = t;
        int[] supply = units.clone();
        int used = 0;
        for (int c : t.corners) {
            if (opp[c] + 1 > MAX_UNITS_PER_CORNER) return null;
            int need = opp[c] + 1 - destined[c];
            for (int s : byDistance[c]) {
                if (need <= 0 || dist[s][c] >= INF) break;
                if (supply[s] <= 0) continue;
                int take = Math.min(need, supply[s]);
                supply[s] -= take;
                need -= take;
                used += take;
                plan.pulls.add(new int[]{s, c, take});
                plan.eta = Math.max(plan.eta, dist[s][c]);
            }
            if (need > 0) return null;
        }
        // the capture must be scored at least once before the game ends
        if (plan.eta >= turnsLeft - 1) return null;
        double value = (t.owner == OPP ? OPP_TRIANGLE_VALUE : 1) * (turnsLeft - 1 - plan.eta);
        plan.score = value / (1.0 + used * (plan.eta + 1));
        return plan;
    }

    // 4. units standing on a used triangle step out so that it becomes capturable again
    static void vacateUsedTriangles() {
        int[] goal = goalDistances();
        for (Triangle t : triangles) {
            if (!t.blocked()) continue;
            boolean possible = true;
            for (int c : t.corners) {
                if (stay[c] > 0 || arrivals[c] > 0) possible = false;
                if (free[c] > 0 && exit(c, t, goal) < 0) possible = false;
            }
            if (!possible) continue;
            for (int c : t.corners) noEntry[c] = true;
            for (int c : t.corners) {
                if (free[c] > 0) assign(c, exit(c, t, goal), free[c]);
            }
        }
    }

    static int exit(int c, Triangle t, int[] goal) {
        int best = -1;
        for (int x : neighbors[c]) {
            if (t.has(x) || noEntry[x]) continue;
            if (best < 0 || goal[x] < goal[best]) best = x;
        }
        return best;
    }

    /**
     * 5. idle units gather towards the closest corner still lacking units, or chase opponent units.
     * The rear group goes back to the triangles near our start, the others push to the center and beyond.
     */
    static void moveIdleUnits() {
        for (int s = 0; s < houseCount; s++) {
            int rearIdle = rearLeft == null ? 0 : Math.min(rearLeft[s], free[s]);
            if (rearIdle > 0) {
                int target = closestCorner(s, 1);
                if (target >= 0) assign(s, target, rearIdle);
            }
            if (free[s] <= 0) continue;
            int target = closestCorner(s, -1);
            if (target < 0) {
                for (int h = 0; h < houseCount; h++) {
                    if (opp[h] == 0 || h == s || dist[s][h] >= INF || noEntry[nextHop(s, h)]) continue;
                    if (target < 0 || dist[s][h] < dist[s][target]) target = h;
                }
            }
            if (target < 0) target = closestCorner(s, 0);
            if (target >= 0) assign(s, target, free[s]);
        }
    }

    // closest corner lacking units among the remaining targets: rear = 1 near our start only, -1 elsewhere only, 0 all
    static int closestCorner(int s, int rear) {
        int target = -1;
        for (Triangle t : remainingTargets) {
            if (rear != 0 && isRear(t) != (rear > 0)) continue;
            for (int c : t.corners) {
                if (c == s || dist[s][c] >= INF || opp[c] + 1 - destined[c] <= free[c]) continue;
                if (noEntry[nextHop(s, c)]) continue;
                if (target < 0 || dist[s][c] < dist[s][target]) target = c;
            }
        }
        return target;
    }

    // 6. spend owned triangles on new units while units are the bottleneck
    static void growArmy() {
        if (turnsLeft <= SPAWN_MIN_TURNS_LEFT) return;
        int outstanding = 0;
        for (Triangle t : remainingTargets) {
            for (int c : t.corners) outstanding += Math.max(0, opp[c] + 1 - destined[c]);
        }
        int myTotal = total(my) + spawns.size();
        int oppTotal = total(opp);
        int wanted = outstanding;
        if (myTotal < oppTotal + ARMY_MARGIN) wanted = Math.max(wanted, oppTotal + ARMY_MARGIN - myTotal);
        wanted = Math.min(wanted, MAX_SPAWNS_PER_TURN) - spawns.size();

        int[] goal = goalDistances();
        int[] end = endOfTurn();
        while (true) {
            double[] bonus = spawnBonus(end);
            Triangle bestTriangle = null;
            int bestCorner = -1;
            double bestCost = INF;
            for (Triangle t : triangles) {
                if (t.owner != ME || usedTriangles.contains(t)) continue;
                // committed units on the corners would delay the recapture
                double cost = 0;
                for (int c : t.corners) cost += 10 * (stay[c] + arrivals[c]);
                if (oppCanTakeAll(t.corners, end)) cost += 100;
                for (int c : t.corners) {
                    // beyond the wanted amount, only spawns completing a capture or a kill this turn are worth it
                    if (wanted <= 0 && bonus[c] <= 0) continue;
                    double cornerCost = cost + Math.min(goal[c], 50) - bonus[c];
                    if (cornerCost < bestCost) {
                        bestCost = cornerCost;
                        bestTriangle = t;
                        bestCorner = c;
                    }
                }
            }
            if (bestTriangle == null) return;
            spawn(bestTriangle, bestCorner);
            end[bestCorner]++;
            wanted--;
        }
    }

    // value of one more unit on each house at the end of the turn: it completes a capture or a kill
    static double[] spawnBonus(int[] end) {
        double[] bonus = new double[houseCount];
        for (Triangle t : remainingTargets) {
            int missing = -1;
            for (int c : t.corners) {
                if (end[c] > opp[c]) continue;
                if (missing >= 0 || end[c] + 1 <= opp[c]) {
                    missing = -2;
                    break;
                }
                missing = c;
            }
            if (missing >= 0) bonus[missing] += t.owner == OPP ? 100 : 50;
        }
        for (int h = 0; h < houseCount; h++) {
            if (opp[h] == 0) continue;
            int missing = -1;
            for (int nb : neighbors[h]) {
                if (end[nb] > opp[nb]) continue;
                if (missing >= 0 || end[nb] + 1 <= opp[nb]) {
                    missing = -2;
                    break;
                }
                missing = nb;
            }
            if (missing >= 0) bonus[missing] += 20 * opp[h];
        }
        return bonus;
    }

    static void spawn(Triangle t, int corner) {
        spawns.add(t.spawnCommand(corner));
        usedTriangles.add(t);
        spawned[corner]++;
    }

    // distance from each house to the closest corner of a remaining target that still lacks units
    static int[] goalDistances() {
        int[] goal = new int[houseCount];
        Arrays.fill(goal, INF);
        for (Triangle t : remainingTargets) {
            // the opening spawns units towards the front, not back home
            if (!invasionDone && isRear(t)) continue;
            for (int c : t.corners) {
                if (opp[c] + 1 - destined[c] <= 0) continue;
                for (int h = 0; h < houseCount; h++) goal[h] = Math.min(goal[h], dist[h][c]);
            }
        }
        return goal;
    }

    static void assign(int from, int to, int amount) {
        if (amount <= 0) return;
        free[from] -= amount;
        destined[to] += amount;
        if (from == to) {
            stay[from] += amount;
        } else {
            moves[from][to] += amount;
            arrivals[nextHop(from, to)] += amount;
        }
    }

    // units that will certainly be on each house at the end of the turn
    static int[] planned() {
        int[] planned = new int[houseCount];
        for (int h = 0; h < houseCount; h++) planned[h] = stay[h] + arrivals[h] + spawned[h];
        return planned;
    }

    static int[] endOfTurn() {
        int[] end = planned();
        for (int h = 0; h < houseCount; h++) end[h] += free[h];
        return end;
    }

    /**
     * True when the opponent, moving each unit at most one house, can end the turn with strictly more units
     * than 'ours' on every target house (generalized Hall condition of the supply/demand problem).
     */
    static boolean oppCanTakeAll(int[] targets, int[] ours) {
        int m = targets.length;
        if (m == 0 || m > MAX_HALL_TARGETS) return false;
        stamp++;
        int sources = 0;
        for (int i = 0; i < m; i++) {
            int t = targets[i];
            for (int k = -1; k < neighbors[t].length; k++) {
                int u = k < 0 ? t : neighbors[t][k];
                if (opp[u] == 0) continue;
                if (sourceStamp[u] != stamp) {
                    sourceStamp[u] = stamp;
                    sourceMask[u] = 0;
                    sourceHouse[sources++] = u;
                }
                sourceMask[u] |= 1 << i;
            }
        }
        int totalNeed = 0, totalSupply = 0;
        for (int i = 0; i < m; i++) totalNeed += ours[targets[i]] + 1;
        for (int k = 0; k < sources; k++) totalSupply += opp[sourceHouse[k]];
        if (totalSupply < totalNeed) return false;
        for (int subset = 1; subset < (1 << m) - 1; subset++) {
            int need = 0;
            for (int i = 0; i < m; i++) {
                if ((subset >> i & 1) != 0) need += ours[targets[i]] + 1;
            }
            int supply = 0;
            for (int k = 0; k < sources; k++) {
                int u = sourceHouse[k];
                if ((sourceMask[u] & subset) != 0) supply += opp[u];
            }
            if (supply < need) return false;
        }
        return true;
    }

    static int nextHop(int from, int to) {
        for (int n : neighbors[from]) {
            if (dist[n][to] < dist[from][to]) return n;
        }
        return from;
    }

    // house rule: every neighbor where the attacker has strictly more units than the defender
    static boolean surroundedBy(int h, int[] attacker, int[] defender) {
        if (neighbors[h].length == 0) return false;
        for (int n : neighbors[h]) {
            if (attacker[n] <= defender[n]) return false;
        }
        return true;
    }

    static int ownerOf(int h) {
        if (surroundedBy(h, my, opp)) return ME;
        if (surroundedBy(h, opp, my)) return OPP;
        if (my[h] > opp[h]) return ME;
        if (opp[h] > my[h]) return OPP;
        return NONE;
    }

    static int[] housesWithUnits(int[] units) {
        int count = 0;
        for (int u : units) if (u > 0) count++;
        int[] houses = new int[count];
        for (int h = 0, k = 0; h < houseCount; h++) if (units[h] > 0) houses[k++] = h;
        return houses;
    }

    // front of each house, and end of the opening once we own enough of the center
    static void computeZones() {
        front = new int[houseCount];
        for (int h = 0; h < houseCount; h++) {
            int mine = INF, theirs = INF;
            for (int s : startMine) mine = Math.min(mine, dist[s][h]);
            for (int s : startTheirs) theirs = Math.min(theirs, dist[s][h]);
            front[h] = mine >= INF || theirs >= INF ? 0 : mine - theirs;
        }
        if (invasionDone) return;
        int center = 0, owned = 0;
        for (Triangle t : triangles) {
            if (!isCenter(t)) continue;
            center++;
            if (t.owner == ME) owned++;
        }
        invasionDone = turn > INVASION_MAX_TURN || owned >= CENTER_SHARE * center;
        if (invasionDone) System.err.println("turn " + turn + ": opening over, " + owned + "/" + center + " central triangles owned");
    }

    // average front of the corners: negative on our side, around zero in the middle of the map
    static double zone(Triangle t) {
        return (front[t.corners[0]] + front[t.corners[1]] + front[t.corners[2]]) / 3.0;
    }

    static boolean isRear(Triangle t) {
        return zone(t) < -CENTER_WIDTH;
    }

    static boolean isCenter(Triangle t) {
        return Math.abs(zone(t)) <= CENTER_WIDTH;
    }

    static int total(int[] units) {
        int sum = 0;
        for (int u : units) sum += u;
        return sum;
    }

    static void computeDistances() {
        dist = new int[houseCount][houseCount];
        byDistance = new int[houseCount][];
        int[] queue = new int[houseCount];
        for (int start = 0; start < houseCount; start++) {
            int[] d = dist[start];
            Arrays.fill(d, INF);
            d[start] = 0;
            int head = 0, tail = 0;
            queue[tail++] = start;
            while (head < tail) {
                int h = queue[head++];
                for (int n : neighbors[h]) {
                    if (d[n] == INF) {
                        d[n] = d[h] + 1;
                        queue[tail++] = n;
                    }
                }
            }
            // BFS order is sorted by distance, unreachable houses go last
            int[] order = Arrays.copyOf(queue, houseCount);
            int k = tail;
            for (int h = 0; h < houseCount; h++) if (d[h] == INF) order[k++] = h;
            byDistance[start] = order;
        }
    }
}
