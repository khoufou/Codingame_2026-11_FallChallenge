# Simulateur local TryAngle Catch

Fait jouer deux bots l'un contre l'autre sur des centaines de cartes, sans passer par CodinGame.

| Fichier | Rôle |
|---|---|
| `Arena.java` | Moteur du jeu (portage ligne à ligne du package `engine` du referee officiel, [eulerscheZahl/TryAngle-Catch](https://github.com/eulerscheZahl/TryAngle-Catch), sans le viewer) et lanceur de parties |
| `Boss.java` | Traduction Java des boss officiels : `boss1` = Wood 2 (`config/level1/Boss.cs`), `boss2` = Wood 1 (`config/level2/Boss.cs`) |
| `bots/v1/Player.java` | Premier bot, utile comme adversaire faible |
| `bots/wood2/Player.java` | Bot optimisé pour Wood 2 (sans ATTACK) |
| `bots/final/Player.java` | Instantané du bot actuel (avec ATTACK), pour mesurer tes futures modifications |
| `build/` | Généré : classes compilées et sorties d'erreur des bots (`build/logs`). Supprimable à tout moment |

## Lancer des parties

Depuis la racine du projet (PowerShell ou Git Bash, JDK 11 ou plus) :

```bash
java tools/arena/Arena.java -a Player.java -b boss1 -n 50 -swap
```

Les fichiers `.java` passés à `-a` et `-b` sont compilés automatiquement.

Autres exemples :

```bash
java tools/arena/Arena.java -a Player.java -b boss2 -l 2 -n 50 -swap
```

```bash
java tools/arena/Arena.java -a Player.java -b tools/arena/bots/final/Player.java -l 2 -n 30 -swap
```

```bash
java tools/arena/Arena.java -a Player.java -b boss2 -l 2 -n 1 -t 1 -trace
```

Depuis Wood 1, le bot utilise ATTACK : teste-le toujours avec `-l 2`. En ligue 1, le referee ignore les ATTACK et le bot se tromperait dans ses calculs.

| Option | Effet | Défaut |
|---|---|---|
| `-a`, `-b` | Bot A (celui qu'on évalue) et bot B : fichier `.java`, `boss1`, `boss2` ou ligne de commande complète | obligatoires |
| `-n` | Nombre de cartes | 20 |
| `-swap` | Joue chaque carte des deux côtés (recommandé : annule l'avantage de position) | non |
| `-s` | Graine de la première carte (les cartes suivantes utilisent les graines suivantes) | 1 |
| `-t` | Parties jouées en parallèle | 8 |
| `-l` | Ligue : 1 = Wood 2 (MOVE, SPAWN), 2 = Wood 1 (ajoute ATTACK) | 1 |
| `-v` | Une ligne de détail par partie | non |
| `-trace` | Journal tour par tour (à utiliser avec `-n 1 -t 1`) | non |
| `-build` | Dossier des fichiers générés | `tools/arena/build` |
| `-boss` | Source des boss | `tools/arena/Boss.java` |

## Lire le résultat

```text
A = Player.java, B = boss1
A vs B over 8 games: 8 W / 0 L / 0 D  (win rate 100,0%)  avg score A 163,3  B 0,0
A timing: first turn max 388 ms, other turns max 14 ms, avg 1,4 ms
End reasons: {B can't score any more points=3, B is dominated=4, B no units or triangles left=1}
```

- **win rate** : part des parties gagnées par A (un nul compte pour une demi-victoire).
- **score -1** : joueur éliminé (dominé à 80 % des triangles, plus d'unités ni de triangles, timeout, commande invalide). Les moyennes comptent ces scores comme 0.
- **timing** : temps de réponse de A. Le premier tour inclut le démarrage de la JVM. Les temps sont gonflés quand plusieurs parties tournent en parallèle : pour une mesure fiable, utilise `-t 1`. Les limites CodinGame sont de 1000 ms au premier tour et de 50 ms ensuite.
- **End reasons** : `turns` signifie que la partie est allée jusqu'au bout des 200 tours.

## Valider une modification du bot

1. Modifie `Player.java` à la racine du projet.
2. Joue-le contre l'instantané avec au moins 30 cartes et `-swap` (60 parties) :

   ```bash
   java tools/arena/Arena.java -a Player.java -b tools/arena/bots/final/Player.java -l 2 -n 30 -swap
   ```

3. Avec 60 parties, l'écart-type du taux de victoire est d'environ 6 points : un résultat entre 44 % et 56 % ne prouve rien. Augmente `-n` pour trancher.
4. Si la nouvelle version gagne nettement, copie-la dans `bots/final/` pour qu'elle devienne la nouvelle référence. Vérifie aussi qu'elle bat toujours `boss2` avec `-l 2`.

## Limites connues

- Le moteur n'a pas été comparé partie par partie au moteur officiel. Il reprend son code source, et sa symétrie est vérifiée : boss contre boss donne 50 %.
- Les commandes `ADD_PATH` et `REMOVE_PATH` de la ligue Bronze ne sont pas simulées. `ATTACK` l'est, avec `-l 2`.
- Les boss ont la même logique que les originaux, mais leurs tirages aléatoires diffèrent (générateur Java au lieu de C#).
