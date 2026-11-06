import java.util.*;

/**
 * Checks the simulation inside Player.java against the arena engine: on random maps, every turn is played in both
 * and the units, triangle owners, capture flags and scores are compared. Both players follow Player's base heuristic,
 * with random commands mixed in to exercise the referee's edge cases (invalid attacks, oversized moves, double spawns).
 * Usage, from the project root:
 *   javac -d tools/arena/build/simtest Player.java tools/arena/Arena.java tools/arena/SimTest.java
 *   java -cp tools/arena/build/simtest SimTest [games] [firstSeed]
 */
public class SimTest {
    public static void main(String[] args) {
        int games = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        long firstSeed = args.length > 1 ? Long.parseLong(args[1]) : 1;
        int turns = 0, mismatches = 0, randomTurns = 0;
        for (long seed = firstSeed; seed < firstSeed + games; seed++) {
            Random random = new Random(seed);
            Arena.Board board = new Arena.Board(new Random(seed));
            Arena.TaskManager manager = new Arena.TaskManager();
            for (int turn = 1; turn <= 200; turn++) {
                Scanner in = new Scanner(board.getInput(turn == 1, 0, true));
                if (turn == 1) Player.readInit(in);
                Player.realTurn = turn;
                Player.readTurn(in);
                Player.Actions[] actions = new Player.Actions[2];
                for (int p = 0; p < 2; p++) {
                    if (random.nextInt(4) == 0) {
                        actions[p] = randomActions(random, p);
                        randomTurns++;
                    } else {
                        actions[p] = Player.plan(Player.real, p, Player.BASE);
                    }
                }
                Player.State predicted = Player.real.copy();
                Player.simulate(predicted, actions[0], actions[1]);
                for (int p = 0; p < 2; p++) {
                    if (!manager.parseTasks(p, board, actions[p].commands(), 3)) throw new IllegalStateException("invalid command");
                }
                do board.applyActions(manager); while (manager.hasTasks(board));
                while (board.finalizeTurn()) ;
                turns++;
                String diff = compare(board, predicted);
                if (diff != null) {
                    mismatches++;
                    System.out.println("seed " + seed + " turn " + turn + ": " + diff + "\n  P0: " + actions[0].commands() + "\n  P1: " + actions[1].commands());
                    if (mismatches >= 10) break;
                }
                if (board.unitCount[0] + board.triangleCount[0] == 0 || board.unitCount[1] + board.triangleCount[1] == 0) break;
            }
            if (mismatches >= 10) break;
        }
        System.out.println(turns + " turns checked (" + randomTurns + " random command sets), " + mismatches + " mismatches");
    }

    static Player.Actions randomActions(Random random, int p) {
        Player.Actions a = new Player.Actions();
        int n = Player.houseCount, tc = Player.triangleCount;
        int[] units = Player.real.units[p];
        for (int k = random.nextInt(3); k > 0; k--) {
            int t = random.nextInt(tc);
            int target = random.nextBoolean() ? random.nextInt(n) : randomNeighbor(random, Player.cornersOf[t][random.nextInt(3)]);
            a.attacks.add(new int[]{t, target});
        }
        for (int k = random.nextInt(8); k > 0; k--) {
            int from = random.nextInt(n);
            if (random.nextInt(3) > 0) {
                int tries = 0;
                while (units[from] == 0 && tries++ < 50) from = random.nextInt(n);
            }
            a.moves.add(new int[]{from, random.nextInt(n), random.nextInt(6) - 1});
        }
        for (int k = random.nextInt(5); k > 0; k--) {
            int t = random.nextInt(tc);
            a.spawns.add(new int[]{t, Player.cornersOf[t][random.nextInt(3)]});
        }
        return a;
    }

    static int randomNeighbor(Random random, int house) {
        int[] nb = Player.neighbors[house];
        return nb.length == 0 ? house : nb[random.nextInt(nb.length)];
    }

    static String compare(Arena.Board board, Player.State s) {
        for (Arena.Node node : board.nodes) {
            for (int p = 0; p < 2; p++) {
                if (node.units[p] != s.units[p][node.id]) return "house " + node.id + " P" + p + " units " + node.units[p] + ", predicted " + s.units[p][node.id];
            }
        }
        for (int t = 0; t < board.triangles.size(); t++) {
            Arena.Triangle tri = board.triangles.get(t);
            if (tri.owner != s.owner[t]) return "triangle " + t + " owner " + tri.owner + ", predicted " + s.owner[t];
            for (int p = 0; p < 2; p++) {
                if (tri.canCapture[p] != s.canCapture[p][t]) return "triangle " + t + " canCapture P" + p + " " + tri.canCapture[p] + ", predicted " + s.canCapture[p][t];
            }
        }
        for (int p = 0; p < 2; p++) {
            if (board.score[p] != s.score[p]) return "score P" + p + " " + board.score[p] + ", predicted " + s.score[p];
        }
        return null;
    }
}
