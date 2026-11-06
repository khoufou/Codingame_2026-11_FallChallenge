# Simulateur local TryAngle Catch

Fait jouer deux bots l'un contre l'autre sur des centaines de cartes, sans passer par CodinGame.

| Fichier | Rôle |
|---|---|
| `Arena.java` | Moteur du jeu (portage ligne à ligne du package `engine` du referee officiel, [eulerscheZahl/TryAngle-Catch](https://github.com/eulerscheZahl/TryAngle-Catch), sans le viewer) et lanceur de parties |
| `Boss.java` | Traduction Java des boss officiels : `boss1` = Wood 2 (`config/level1/Boss.cs`), `boss2` = Wood 1 (`config/level2/Boss.cs`), `boss3` = Bronze et Silver (`config/level3/Boss.cs`, identique à `config/Boss.cs`) |
| `bots/v1/Player.java` | Premier bot, utile comme adversaire faible |
| `bots/wood2/Player.java` | Bot optimisé pour Wood 2 (sans ATTACK) |
| `bots/attack/Player.java` | Bot avec ATTACK, avant la stratégie offensive (même force que `traps`) |
| `bots/traps/Player.java` | Comme `defense`, mais il annulait les déplacements vers les maisons où l'adversaire pouvait encercler ses unités. Il ne gagne que 24 % des parties contre `defense` |
| `bots/defense/Player.java` | Bot précédent (ATTACK + poussée vers l'avant + un tiers de l'armée vers l'arrière, sans éviter les pièges d'encerclement) : il défend ses triangles. Même force que `invasion` |
| `bots/invasion/Player.java` | Version « tout attaque » (voir plus bas), avant le nouveau classement des captures |
| `bots/final/Player.java` | Instantané du bot actuel (`invasion` + captures les plus proches d'abord), pour mesurer tes futures modifications |
| `build/` | Généré : classes compilées et sorties d'erreur des bots (`build/logs`). Supprimable à tout moment |

## Lancer des parties

Depuis la racine du projet (PowerShell ou Git Bash, JDK 11 ou plus) :

```bash
java tools/arena/Arena.java -a Player.java -b boss1 -n 50 -swap
```

Les fichiers `.java` passés à `-a` et `-b` sont compilés automatiquement.

Autres exemples :

```bash
java tools/arena/Arena.java -a Player.java -b boss3 -l 3 -n 50 -swap
```

```bash
java tools/arena/Arena.java -a Player.java -b tools/arena/bots/final/Player.java -l 3 -n 30 -swap
```

```bash
java tools/arena/Arena.java -a Player.java -b boss3 -l 3 -n 1 -t 1 -trace
```

Le bot utilise ATTACK depuis Wood 1, et Bronze ajoute ADD_PATH et REMOVE_PATH. Bronze, Silver et Gold ont les mêmes règles : en Silver, teste toujours avec `-l 3`. Dans une ligue plus basse, le referee ignore les commandes qu'elle ne connaît pas et le bot se tromperait dans ses calculs.

| Option | Effet | Défaut |
|---|---|---|
| `-a`, `-b` | Bot A (celui qu'on évalue) et bot B : fichier `.java`, `boss1`, `boss2`, `boss3` ou ligne de commande complète | obligatoires |
| `-n` | Nombre de cartes | 20 |
| `-swap` | Joue chaque carte des deux côtés (recommandé : annule l'avantage de position) | non |
| `-s` | Graine de la première carte (les cartes suivantes utilisent les graines suivantes) | 1 |
| `-t` | Parties jouées en parallèle | 8 |
| `-l` | Ligue : 1 = Wood 2 (MOVE, SPAWN), 2 = Wood 1 (ajoute ATTACK), 3 = Bronze, Silver et Gold (ajoute ADD_PATH et REMOVE_PATH) | 1 |
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
   java tools/arena/Arena.java -a Player.java -b tools/arena/bots/final/Player.java -l 3 -n 30 -swap
   ```

3. Avec 60 parties, l'écart-type du taux de victoire est d'environ 6 points : un résultat entre 44 % et 56 % ne prouve rien. Augmente `-n` pour trancher.
4. Si la nouvelle version gagne nettement, copie-la dans `bots/final/` pour qu'elle devienne la nouvelle référence. Vérifie aussi qu'elle bat toujours `boss3` avec `-l 3`.

## Version « tout attaque »

Le bot actuel (`Player.java`, instantané dans `bots/invasion`) est la variante offensive de `defense` :

- aucune unité ne reste en défense : un triangle que l'adversaire va prendre est transformé en unité avant ;
- l'armée ne se divise jamais (pas de groupe arrière) ;
- dès que son armée fait 1,5 fois celle de l'adversaire, elle passe à l'attaque totale. Une capture vaut jusqu'à 7 fois plus près du départ adverse, et 7 fois moins près du nôtre. Les unités libres prennent les maisons autour des piles adverses pour les encercler.

Elle a la même force que `defense` (52,5 % sur 600 parties) et bat `boss2` à 100 %. Contre `boss3`, le boss de Silver, elle gagne 73 % des parties (300 parties en `-l 3`).

```bash
java tools/arena/Arena.java -a Player.java -b tools/arena/bots/defense/Player.java -l 2 -n 150 -swap
```

Attaquer dès le premier tour perd. Avec ses 3 unités de départ, le bot capture trop peu de triangles pendant que l'adversaire fait grossir son armée : 5 unités contre 25 au tour 13. Mesures contre `traps` (300 parties) : invasion forte dès le début 14 %, tous les ingrédients offensifs combinés 11 %, combats uniquement (aucune capture neutre) 0 %.

## Captures les plus proches d'abord

Le bot choisit ses captures une par une, en commençant par celle qui a le meilleur score. Avant, le score était la valeur du triangle divisée par `1 + unités × (tours de trajet + 1)` : une capture lointaine ou gourmande en unités passait après tout le reste. Le bot actuel divise par `(tours de trajet + 1) × (1 + 0,1 × unités)` : il prend d'abord ce qui est proche, même si cela demande plus d'unités, et il tient ainsi beaucoup plus de triangles.

| Mesure (`-l 3`, 300 parties) | Victoires | Score moyen nous / eux |
|---|---|---|
| Contre `invasion`, graines 500 et plus | 65,3 % | 140 / 68 |
| Contre `invasion`, graines 2000 et plus | 56,7 % | 116 / 80 |
| Contre `boss3` | 76,7 % (73 % avant) | 74 / 40 |

Autres formules essayées (contre `invasion`, graines 500) : poids des unités 0 / 0,05 / 0,2 / 0,4 = 61 / 66 / 62 / 58 %, trajet au carré 60 %, priorité au coût en unités 40 %. Pistes sans effet ou nuisibles pour tenir plus de triangles : expansion compacte autour de nos triangles (35 à 43 %), captures près des pièges d'encerclement (47 %), 12 unités par coin (50 %), moins d'apparitions (50 %).

## Essais en Silver

Il n'y a pas de règle de « boost » : la seule façon de créer des unités en plus est d'ajouter des chemins (ADD_PATH) qui forment de nouveaux triangles. Un chemin coûte un triangle et 1 unité par coin, alors qu'un SPAWN avec ce triangle donne une unité gratuite. Mesures contre le bot actuel en `-l 3` (300 parties chacune) :

| Variante | Victoires |
|---|---|
| ADD_PATH dès qu'un chemin crée un triangle tenu ce tour-ci | 24 % |
| Même chose, en comptant aussi les nouveaux triangles neutres | 0,5 % |
| ADD_PATH seulement s'il crée 2 triangles tenus, et/ou avec 30 unités ou plus | 46 à 50 % |
| ATTACK dès 3 / 2 / 1 unité adverse (au lieu de 4) | 39 / 16 / 2 % |

Aucune ne fait mieux que le bot actuel : il n'utilise pas ADD_PATH et garde ATTACK à partir de 4 unités.

Contre `boss3` (300 parties en `-l 3`, graines 3000 et plus), le bot actuel gagne 73 %. Le boss attaque chaque tour, dès qu'il a plus de 20 unités, la maison voisine de ses triangles où nous avons le plus d'unités (2 ou plus), et il fait apparaître des unités avec tous ses triangles. Ces essais n'ont rien amélioré :

| Variante | Contre `boss3` | Contre le bot actuel |
|---|---|---|
| Plus d'apparitions (6, 12 ou 50 par tour, à chaque tour) | 73 % | non mesuré |
| Unités libres envoyées une par une au lieu d'en groupe | 73 % | non mesuré |
| Pas de pile de 2 unités ou plus sur les maisons que le boss peut attaquer | 72 % | non mesuré |
| ATTACK dès 3, 5 ou 6 unités | 70 / 73 / 73 % | non mesuré |
| Annuler les déplacements des unités libres vers un piège d'encerclement | 73 % | 45 % |
| Annuler tous les déplacements vers un piège d'encerclement | 60 % | 6 % |

Bot qui s'adapte à l'adversaire : chaque tour, il repère les maisons vidées depuis le tour précédent. Si un triangle adverse voisin vient d'être utilisé, c'est une ATTACK, et le bot évite ensuite les piles de cette taille près des triangles armés de l'adversaire. Sinon, c'est un encerclement, et ses unités libres évitent ensuite les pièges d'encerclement. La détection fonctionne (le boss est bien reconnu comme attaquant), mais la réponse n'apporte rien : 77 % contre `boss3` et 46 à 49 % contre le bot actuel, avec le nouveau classement des captures (référence : 76,7 % et 50 %).

`traps` bat `boss3` un peu plus souvent (77 %), mais il ne gagne que 24 % contre `defense` et le bot actuel : le bot actuel reste le meilleur compromis.

## Limites connues

- Le moteur n'a pas été comparé partie par partie au moteur officiel. Il reprend son code source, et sa symétrie est vérifiée : boss contre boss donne 50 %.
- `ATTACK` est simulé avec `-l 2`, `ADD_PATH` et `REMOVE_PATH` avec `-l 3`. Les résultats en `-l 2` sont identiques avant et après l'ajout des chemins.
- Les boss ont la même logique que les originaux, mais leurs tirages aléatoires diffèrent (générateur Java au lieu de C#).
