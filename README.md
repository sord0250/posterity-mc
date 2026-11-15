# Posterity MC

Two Paper plugins for the Posterity server: **Posterity NPCs**, which turns villagers into
working NPC employees, and **Posterity Kingdoms**, a stripped-down way to mark whose base is
whose so those NPCs can be told apart. Neither plugin requires the other, but together a new
kingdom comes with two free NPCs to put to work.

Needs [Paper](https://papermc.io) 1.21+ (api-version 26.2) and, for the economy bits,
[Vault](https://www.spigotmc.org/resources/vault.34315/) with any Vault-compatible economy plugin.

## Posterity Kingdoms

Claiming a kingdom is just planting a flag: one block becomes your kingdom's **Core**. There's
no territory to claim, nothing to buy or sell, and NPCs can build anywhere - a kingdom only
exists so NPCs can be sorted by whose base they belong to. Land claiming/protection may come
later as a separate feature.

| Command | What it does |
|---|---|
| `/kingdom claim <name>` | Claims the block you're standing on as your kingdom's Core. Your first kingdom's first two NPCs (with Posterity NPCs installed) spawn next to you, free to hire; until you give one a job, it wanders no more than 50 blocks from the Core. |
| `/kingdom info [kingdom]` | Shows a kingdom's owner, rulers and Core location. |
| `/kingdom addruler <player>` / `removeruler <player>` | Owner-only: puts another player in charge of the kingdom too (or removes them). A ruler can buy/hire NPCs that count as the kingdom's, same as the owner. |
| `/kingdom leave` | A ruler (not the owner) gives up their place in the kingdom. |
| `/kingdom money <amount>` | **Admin-only stub.** Gives the sender money through Vault. Standing in for a real economy until merchants and trading are built out. |
| `/kingdom delete [kingdom]` | Owner or admin: disbands a kingdom. |

Aliases: `/kd`, `/kingdoms`. Permissions: `posteritykingdoms.use` (default: everyone),
`posteritykingdoms.admin` (default: op).

## Posterity NPCs

Shift-right-click a grown-up villager (or `/employees hire <job>`) to hire it: the villager is
replaced by a skinned NPC that does a job - farming, chopping trees, mining, quarrying, building
from a schematic, smelting, ranching, trading, guarding, running courier routes between chests,
and more. Employees are paid wages through Vault, can own a home, and store their work in
regular or virtual chests. If Posterity Kingdoms is installed, a newly hired NPC is automatically
tagged with the kingdom its owner (or ruler) belongs to, so `/employees info` shows which
kingdom it's part of.

Main commands (aliases `/emp`, `/staff`):

| Command | What it does |
|---|---|
| `/employees list` / `info <name>` | Lists your employees, or details on one. |
| `/employees hire <job>` | Hires the villager you're looking at. |
| `/employees fire <name>` | Lets an employee go (a former villager becomes one again). |
| `/employees area`, `storage`, `supply`, `filter` | Sets where an employee works and where it stores/pulls materials. |
| `/employees build`, `blueprints` | Builder jobs: assign a schematic to build. |
| `/employees skin`, `rename`, `herd` | Cosmetic and job-specific settings. |
| `/employees pause` / `resume` / `move` / `tp` | Pauses work, relocates, or teleports to an employee. |
| `/employees pay <name>` | Pays an on-strike employee's wage manually. |

A few related commands round things out: `/sort` (tidy nearby or virtual chests by category),
`/map` (web map + 3D build planner sign-in), `/trades` (find villagers selling an item),
`/guide` (walk, don't teleport, to a warp/coordinate/employee).

## Building

Each plugin is its own Maven project:

```
cd plugins/posterity-npcs/source && mvn package
cd plugins/posterity-kingdoms/source && mvn package
```

The jars land in each project's `target/` folder; drop them into your server's `plugins/` folder.
