# Posterity MC

Two Paper plugins for the Posterity server: **Posterity NPCs**, which turns villagers into
working NPC employees, and **Posterity Kingdoms**, a stripped-down way to mark whose base is
whose so those NPCs can be told apart. Neither plugin requires the other, but together a new
kingdom comes with two free NPCs to put to work, and each NPC's kingdom shows up on both.

## Requirements

| | Required? | What it's for |
|---|---|---|
| [Paper](https://papermc.io) 26.2 or newer, Java 25 | **Required** | Both plugins (`api-version: 26.2`). |
| [Vault](https://www.spigotmc.org/resources/vault.34315/) + any Vault-compatible economy plugin | Optional | NPCs selling to merchants and buying build materials; `/kingdom money` (an economy stand-in). Without it, those features just say so and nothing breaks. |
| [WorldEdit](https://enginehub.org/worldedit) | Optional | Zones from a WorldEdit selection, saving a blueprint from a selection, and the built-in starter houses (only added to the NPC Homes catalog if WorldEdit is present). Picking a work area or no-work zone by flying over it ("pick from above") doesn't need WorldEdit at all. **Make sure the WorldEdit build you install actually supports your server's exact Minecraft/Paper version** - an incompatible build can fail to enable and, in some cases, crash the whole server on startup rather than just disabling itself. |
| [VirtualChests](https://www.spigotmc.org/resources/), Essentials, CoreProtect, BlueMap | Optional | Deliver to `/chest` numbers instead of placed chests, Vault economy backend, block-change logging, map layers. |

## Setup

1. Install Paper and Java 25, and start the server once (accept the EULA) so it's working on its own first.
2. Build both plugins (see **Building** below), or get their jars from wherever you got this repo.
3. Drop `ArcEmployees-<version>.jar` and `PosterityKingdoms-<version>.jar` into the server's `plugins/` folder. Add Vault and/or WorldEdit there too if you want those features (see **Requirements**).
4. Start the server. Each plugin writes its own `config.yml` into `plugins/ArcEmployees/` and `plugins/PosterityKingdoms/` the first time it runs - stop the server, adjust settings there (currency symbol, how many NPCs one player or one kingdom may have, how much food hiring costs, the flag's emerald price, etc.), then start it again. `/employees reload` reloads ArcEmployees' settings without a restart.
5. Right-click the **Guide** (an NPC that appears near world spawn on first start) for a four-step walkthrough, or just run `/kingdom claim <name>` to found your first kingdom - its first two NPCs arrive free to hire.
6. `/employees` and `/kingdom` with no arguments each open a screen of buttons for everything below; `/employees help` and `/kingdom help` list the commands as text.

## Posterity Kingdoms

Claiming a kingdom is just planting a flag: one block becomes your kingdom's **Core**. There's
no territory to claim, nothing to buy or sell, and NPCs can build anywhere - a kingdom only
exists so NPCs can be sorted by whose base they belong to. Land claiming/protection may come
later as a separate feature.

| Command | What it does |
|---|---|
| `/kingdom` or `/kingdom info` | Your kingdom's info screen: owner, rulers, Core, NPC count, flag, and buttons for everything else. Named with a kingdom (`/kingdom info <kingdom>`), it's a plain-text lookup of someone else's instead. |
| `/kingdom claim <name>` | Claims the block you're standing on as your kingdom's Core. Your first kingdom's first two NPCs (with Posterity NPCs installed) spawn next to you, free to hire; until you give one a job, it wanders no more than 50 blocks from the Core. |
| `/kingdom addruler <player>` / `removeruler <player>` | Owner-only: puts another player in charge of the kingdom too (or removes them). A ruler can buy/hire NPCs that count as the kingdom's, same as the owner. |
| `/kingdom leave` | A ruler (not the owner) gives up their place in the kingdom. |
| `/kingdom flag set` | Owner-only: hold a banner you made on a loom, and it becomes your kingdom's flag design. |
| `/kingdom flag buy` | Any ruler: a copy of the kingdom's flag for a few emeralds (set in `config.yml`), so everyone's flag matches without redoing it on a loom each time. |
| `/kingdom money <amount>` | **Admin-only stub.** Gives the sender money through Vault. Standing in for a real economy until merchants and trading are built out. |
| `/kingdom delete [kingdom]` | Owner or admin: disbands a kingdom, after a confirmation that says what it'll do. |

Aliases: `/kd`, `/kingdoms`. Permissions: `posteritykingdoms.use` (default: everyone),
`posteritykingdoms.admin` (default: op).

## Posterity NPCs

Shift-right-click a grown-up villager (or `/employees hire <job>`) and pick a job: the villager
asks for the tools of the trade and some food (or reserve it, and it waits by its bed until you're
back). Hire it and it's replaced by a skinned NPC that does a job - farming, chopping trees, mining, quarrying, building
from a schematic, smelting, ranching, trading, guarding, running courier routes between chests,
and more. Employees aren't paid: they eat from a pantry chest, keep a working day (work, an
evening off, sleep in any free bed they can reach, or just stay up if there isn't one), show what
they're doing over their heads, can own a home, and store their work in regular or virtual chests.
Clicking one opens its card, a screen of buttons for everything about it. If Posterity Kingdoms is
installed, a newly hired NPC is automatically tagged with the kingdom its owner (or ruler) belongs
to, so its card shows which kingdom it's part of, and that kingdom's info screen shows how many
NPCs it has. One kingdom can have at most a handful of NPCs at a time (set in `config.yml`); the
same goes for one player's own NPCs regardless of kingdom.

Main commands (aliases `/emp`, `/staff`):

| Command | What it does |
|---|---|
| `/employees` | Opens the employee list (icons); clicking one opens its card. |
| `/employees list` / `info <name>` | Lists your employees as text, or details on one. |
| `/employees hire <job>` | Hires the villager you're looking at. |
| `/employees fire <name>` | Lets an employee go (a former villager becomes one again). |
| `/employees area`, `storage`, `supply`, `filter` | Sets where an employee works and where it stores/pulls materials. |
| `/employees build`, `blueprints` | Builder jobs: assign a schematic to build. |
| `/employees skin`, `rename`, `herd` | Cosmetic and job-specific settings. |
| `/employees pause` / `resume` / `move` | Pauses work, or relocates an employee (and its work area) to you. |
| `/employees pantry <name>` | Shows an employee's food, or sets the chest it eats from. |
| `/employees greeter here` / `skin ...` | Admin: moves the Guide to where you're standing, or gives it a different look (same skin options as `/employees skin`). |

A few related commands round things out: `/sort` (tidy nearby or virtual chests by category),
`/map` (web map + 3D build planner sign-in), `/trades` (find villagers selling an item),
`/guide` (walk, don't teleport, to a warp/coordinate/employee - not to be confused with the
Guide NPC, which only talks when you click it).

## Building

Each plugin is its own Maven project:

```
cd plugins/posterity-npcs/source && mvn package
cd plugins/posterity-kingdoms/source && mvn package
```

The jars land in each project's `target/` folder; drop them into your server's `plugins/` folder
(see **Setup** above).
