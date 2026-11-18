# ArcEmployees

> **Credit:** ArcEmployees was written by **[neonsushi](https://github.com/neonsushi)**; the original
> code is his. This copy is part of [Villager Workforce](../README.md) in the Minecraft Posterity
> World project, which adds hiring from villagers, personalities, homes and the built-in upload page.
> This manual covers both.

A Paper plugin for hiring NPC employees who do the repetitive work on a survival server: chopping
trees, farming, mining, quarrying, building (and repairing) schematics, smelting, ranching, guarding,
villager trading, carrying things between chests and keeping you company as a bodyguard. They
deliver to your VirtualChests, can sell what they make to a merchant, and aren't paid: they eat food
from a pantry chest, keep a working day (work, an evening off, a night's sleep) and show what they're
doing over their heads.

Employees are **hired from the world's own villagers**: shift-right-click a grown-up villager and
pick a job. The villager becomes the employee, standing where it stood. (Hiring villagers is free for
now.) Each employee is a vanilla **Mannequin**: a player-shaped entity with a real skin, a name and a job
label. Mannequins have no AI of their own, so ArcEmployees moves them itself. Everyone works at the
speed of a survival player with the same tool.

- Server: Paper 26.2 or newer (Java 25).
- Optional: **Vault** and an economy (EssentialsX) for selling to merchants, **VirtualChests** (deliveries and storage), **WorldEdit** (selecting areas, reading
  schematics, and the built-in starter houses - without it, picking a work area or no-work zone by
  flying over it still works fine; make sure whichever WorldEdit build you install supports your
  server's exact version, since a mismatched one can fail badly instead of just disabling itself),
  **ArcShop** (merchant prices), **EssentialsX** (mail to offline owners),
  **CoreProtect** (logs employees' block changes).

## Contents

1. [Getting started](#getting-started)
2. [The jobs](#the-jobs)
3. [Menus](#menus)
4. [Work areas](#work-areas)
5. [Storage: where things go](#storage-where-things-go)
6. [Supplies: where materials come from](#supplies-where-materials-come-from)
7. [Building blueprints](#building-blueprints)
8. [Selling and the market](#selling-and-the-market)
9. [Food, the working day and status](#food-the-working-day-and-status)
10. [Reports and profit](#reports-and-profit)
11. [CoreProtect logging](#coreprotect-logging)
12. [Control boards and the control panel](#control-boards-and-the-control-panel)
13. [Sorting chests: /sort](#sorting-chests-sort) (and `/guide` to warps)
14. [All commands](#all-commands)
15. [Configuration](#configuration)
16. [Building and deploying](#building-and-deploying)
17. [How it works inside](#how-it-works-inside)

---

## Getting started

1. Find a grown-up villager where the work is and **shift-right-click** it: a menu of jobs opens. Click
   a job (and confirm) and the villager becomes your employee: it takes on a player's look (a skin) and
   gets to work. Or look at the villager and type `/emp hire lumberjack`. Hiring a villager is free
   for now (see [Hiring villagers](#hiring-villagers)).
2. The new employee works in an area around where the villager stood (outlined in particles for a while).
   Change it with your WorldEdit wand selection: `//wand`, select two corners, then
   `/emp area <name> worldedit` (or the **Work area** button in their menu).
3. Their output goes to a VirtualChests chest (`/chest 10` for the first lumberjack, and so on; see
   [chest groups](#chest-groups)). Open it from their menu.
4. Right-click an employee, or use `/emp`, to manage them.

`/emp` is short for `/employees`; `/staff` and `/arcemployees` work too. `/emp help` lists commands.

### Hiring villagers

- **Who can be hired:** any grown-up villager that came to the world the normal way: generated with a
  village, born there, or cured from a zombie villager. Not babies, sleeping villagers, ones on a lead
  or riding something, ones in a trader's [stall](#trading-hall-stalls-traders), ones on someone else's
  land (ArcEstate), or ones from spawn eggs or commands (`villager-hiring.natural-only`). Shift-right-
  clicking with a name tag or lead in hand still names or leashes it as usual.
- **What happens:** the villager is taken out of the world and an employee takes its place: a Mannequin
  with one of the game's player skins (change it in its menu, or `/emp skin`). It keeps the villager's
  name tag as its name if it had one, otherwise it gets a name from the list in `config.yml`. Its trades
  are gone: it works for you now.
- **Cost:** the villager asks for the **tools of the job** (`jobs.<job>.hire-items`: an iron hoe for a
  farmer, an iron pickaxe for a miner...) and **food** (`hiring.food`, 16 bread, plus 2 more for every
  employee you already have, up to 64). Pick a job and a window shows the villager's offer, with a tick
  or a cross by each thing so you see what you're missing. The tools become theirs; the food goes into
  their food store. A kingdom's settlers are free.
- **Reserving:** can't afford it yet? Click **Reserve for me** in that window. The villager walks to
  its bed and waits there, standing still and unhurt, for `hiring.reserve-days` (7) real days; only you
  can hire it meanwhile. Shift-right-click it when you're back to see the offer again (or **Let them
  go**). Up to `hiring.max-reserved` (3) at a time.
- **The village refills:** `villager-hiring.replenish.delay-minutes` (20) after a hire, a baby villager is
  born where it stood, so villages don't empty out. Only up to the number of villagers that were round
  that spot (`radius`, 48 blocks) before the hire, never more, and never past `max-villagers-nearby`
  (30). If nobody is near the village then, it happens next time its chunk is loaded. Waiting babies are
  kept in `villager-hiring.yml` over restarts.
- **Kingdom settlers:** with Kingdoms & Claims (ArcEstate) installed, founding your **first** kingdom
  (`/kingdom found`) brings `villager-hiring.kingdom-settlers` (2) settler villagers to the Core, with
  name tags. Only the founder can hire them (shift-right-click, pick a job), it's free, and it works
  wherever they've wandered. They can't be hurt until they're hired, and hiring them doesn't bring a
  baby villager (they didn't come from a village). Disbanding and founding again brings no more:
  each player gets settlers once (kept in `villager-hiring.yml`). `0` turns settlers off.
- **Firing:** a fired employee that was a villager turns back into a grown-up villager where it stands
  (`release-on-fire`). It can be hired again (keeping its old name), but that doesn't refill its village
  a second time.
- **Only villagers:** with `villager-hiring.villagers-only` (on), hiring someone out of nothing where you
  stand (`/emp hire <job>` with no villager in front of you, or the **Hire someone** menu) is for admins
  only; everyone else is pointed to a villager.

---

## The jobs

All employees work at survival-player speed (`work-speed: 1.0`): real block-breaking times for their
tool, the pause between blocks, a right-click every 4 ticks, a sword swing every 12.5 ticks.

### Lumberjack
Fells every grown tree in its area (logs bottom-up, then the tree's own leaves), replants the same
sapling and keeps the saplings, apples and sticks. **Auto-planting** lays out new trees on open dirt or
grass in rows (3 apart in a row, rows 5 apart: `spacing`, `row-spacing`), preferring single-trunk
saplings; dark and pale oak are planted as 2x2 squares only when that's all it has. It keeps 64
saplings of each kind and fetches more from its own or your other employees' chests when it runs out.
Natural leaves are required to count as a tree, so log buildings are never chopped.

### Farmer
Harvests ripe wheat, carrots, potatoes, beetroot, nether wart, cocoa, sweet berries, melons and
pumpkins, cuts sugar cane, cactus and bamboo down to the bottom block, and replants from the harvest.
Empty farmland is replanted with what grew there before (or what its neighbours grow). Seeds come
from its backpack, then storage.

### Miner
Explores its area (49x49 from bedrock to 10 above you when hired) for the blocks in its **filter**
(every ore by default). It finds ores open to a cave or the sky, as a player exploring would, and
tunnels through natural stone and dirt to reach them, never through anything built. It pillars up
and bridges gaps with stone it keeps (up to 64), mines the whole vein, and plans long trips in
stretches. `x-ray: true` also finds buried ores. Ores next to lava are left alone. The area is
surveyed continuously in the background ("Surveying the area for ores (12 chunks to go)").

**Roaming** (the map button in its menu, or `/emp roam <name> on`): once its area is mined out, the
miner moves on to the next area-sized patch of ground, spiralling out from where it started, up to
`roam-distance` (256) blocks, only into land that's already been generated. You're told each time it
moves; `/emp roam <name> back` (or right-click the button) calls it back to its first area.

### Quarry miner
Digs out its whole area layer by layer from the top, leaving one block per layer as a spiral
staircase along the wall. It plugs water and lava with stone it has dug and leaves those plugs as
dams. Blocks bordering liquid outside the area, and blocks its pickaxe can't harvest, stay as part of
the wall. Liquids it can't reach are left alone. **Reach down to bedrock** (area menu) or
`/emp area <name> bedrock` extends the area to the bottom of the world. If it's ever stranded on a ledge or a
lone block it can't step down from, it jumps down to the pit floor (open to the sky, never a sealed cave pocket below it; diggers drop
up to 12 blocks rather than tunnel all the way round). **Move here** starts the moved quarry at the floor
you stand on, as deep as before; path blocks it puts down only ever go below the layer it's digging. Its filter lists drops to
throw away (e.g. cobblestone). If the floor is dug out from under any employee, they drop to the
next floor below.

### Builder
Builds a blueprint (schematic file) block by block, bottom-up. See
[Building blueprints](#building-blueprints).
It has no work area of its own: its site is wherever you place the blueprint. Its controls are in
the **Build menu**: `/emp build <name>`, or the **Build** button in its menu.

**Employees in other worlds:** one hired in a world that's only loaded on demand (the `/studio`
world) isn't loaded after a restart until that world is. Meanwhile it's listed as **Away** in
`/emp list` and the employee menu, keeps its name (a new hire can't take it), and comes back by
itself when the world loads.

### Smelter / cook
Runs the furnaces, smokers and blast furnaces in its area: loads what its filter lists (raw ores and
raw meat and fish by default; food goes to smokers and ores to blast furnaces when there are any),
adds just enough fuel (one coal smelts 8 items), takes out the results and delivers them. Smelting
happens in the real furnaces at vanilla speed. Inputs and fuel come from the backpack, then the
team's storage.

### Rancher
Looks after the animals in its area (the pen): breeds each kind up to the herd size (10 by default,
`/emp herd <name> <n>`) with feed from storage, culls the oldest adults above it at sword speed,
shears sheep, milks cows and goats into empty buckets you give it, and collects eggs, wool and meat.
Never touches named, tamed, leashed or baby animals.

### Guard
Patrols its area and fights hostile mobs within 8 blocks of it at sword speed, collecting their drops.
With torches in its backpack it lights up dark ground. It leaves alone: endermen, piglins and the like
(`jobs.guard.ignore`), mobs that are name-tagged, leashed, in a boat or minecart or kept from
despawning (iron golem farm zombies), and mobs inside its [no-work zones](#no-work-zones) (for a
guard, places it doesn't fight). Mobs can never target employees, so a creeper a guard fights never
explodes. Employees and merchants never go through portals.

### Rancher: several pens
One rancher can look after several pens in its work area (a cow pen and a chicken pen): it walks
between them through fence gates, which it opens and closes behind it, and it can feed, shear or cull
over a fence like a player. The herd size counts each kind across the whole area, young ones included.

**Producing:** with `jobs.rancher.keep-breeding` (on), a full herd keeps breeding (a tenth of the
herd over, at least two) and the oldest grown-ups above the herd size are culled (never below two grown-ups), so a ranch turns out meat,
leather and feathers steadily instead of sitting at its herd size. It needs breeding food in its
supply (wheat, carrots, seeds...). When breeding it
only pairs animals that can reach each other (nothing solid between them), so two cows in different
pens are never both fed for nothing.

### Villager trader
Works your trading hall (its work area): you pick exact offers (a villager and one of its trades,
e.g. 32 sticks for an emerald) in **Trades** (its menu, or `/emp trades <name>`), and whenever that
villager has stock it walks up and makes the trade, one per right-click, paying from its backpack and
your storage (keeping up to 256 of the goods) and delivering what it gets. Villagers restock at their
workstations as usual; their prices (demand, discounts) are respected.

Short of what a trade costs, it **crafts** it from what's in your storage, using the game's own
recipes up to three steps deep: your lumberjacks' oak logs become planks and then sticks. It fetches
the raw materials, crafts in its backpack, and trades. Crafting can be turned off (the **Crafting**
button in its Trades menu, or `/emp craft <name> off`). It never crafts with what's on its
**Never craft with** list (diamonds, netherite, emerald blocks... to begin with, from
`jobs.trader.never-craft-with`; change it in the menu or with `/emp craft <name> never add|remove
<item>`), nor with the goods its other picked trades take.

### Bodyguard
Follows you, staying a few blocks away, and fights hostile mobs that come within 12 blocks of you
(`protect-radius`), picking up what they drop. With torches in its backpack it lights dark spots
around you. If you get far ahead, fly, or change worlds it catches up by teleporting, like a tamed
wolf; while you fly it keeps to the ground under you. What it picks up stays in its backpack while
it follows you (it never walks back to a storage chest at its post to drop it off, VirtualChests
storage still gets it as usual) and is delivered once it's back at its post. **Stay** (the button in its menu, or `/emp stay <name>`) sends it back to guard its post (its
work area) exactly like a guard; **Follow** (`/emp follow <name>`) brings it along again. While you're
offline it guards its post. It uses the guard's ignore list and no-work zones too.

**Armour and getting hurt:** put armour in a guard's or bodyguard's backpack and it wears the best
pieces (shown on it, wearing out like yours). Mobs can now go for guards and bodyguards and hurt
them, in melee or with arrows, though never creepers, so nothing gets blown up. Only mobs they'd
fight back can hurt them: one kept in a boat or minecart, named, leashed, swimming or out of reach
leaves them alone, as they leave it. A guard that's hit drops what it's doing and turns on the mob
that hit it. Hurt, they heal a
heart every couple of seconds out of combat. Brought down to nothing they aren't killed: they're
**knocked out** for `jobs.guard.knockout-seconds` (60), lying down, then get up at full health. You're
told who did it. The **Armour and health** button in its menu shows its hearts and what it wears,
and takes the armour off (into your inventory). They keep one bow, one crossbow, a stack of each
kind of arrow and a spare of each armour piece at least as good as what they wear; extra bows and
arrows and armour they've swapped out go to their storage with the mob drops. `jobs.guard.take-damage: false` makes them untouchable
again.

**Bows:** give a guard or bodyguard a bow (or crossbow) and arrows in its backpack and it shoots mobs
more than 4 blocks away, at a player's draw speed, leading the target for the drop, and switches to
its sword up close. It draws the bow like a player (you see the pose), and only shoots when the shot is clear: never past you, a pet, a villager or
another employee (and its arrows can't hurt any of them anyway). Power, Flame, Unbreaking and
Infinity work; without Infinity arrows are used up, and the bow wears out like yours. It keeps its bow
and all its arrows (arrows skeletons drop too).

Several bodyguards each keep their own place around you (behind left, behind right, then the sides,
measured from the way you're walking, not where you look, so they stand still while you do)
and walk at slightly different paces, so they never merge into one. They split up threats: each goes
for the mob nearest to *you* that no other employee is already fighting, so with one mob around, one
bodyguard deals with it while the others stay at your side, and a second mob gets the next one.

### Courier
Carries items between chests along the **routes** you give it (**Routes** in its menu, or
`/emp route <name> add <from> <to> [items...] [keep <n>] [stock <n>]`): lumberjack logs to the
smelter's chest, ores into your sorting room, anything from a `/chest` into real chests in your base.
Each end is a `/chest` number or a placed chest, barrel or other container (in the menu, click
**Take from** and then click the chest in the world; right-click to type a `/chest` number instead).
By command, an end is a number, `look` (the container you're looking at), `click` (click it next, so
`/emp route Cora add click click` picks both chests by clicking) or `x,y,z`.

**Selling:** a route can end at a **merchant** instead (shift-click **Bring to**, or
`/emp route <name> add <from> merchant [items...] [keep <n>]`): the courier picks up only what
merchants buy and will pay for today, walks it to the nearest merchant, sells it (stopping at the
day's limit like any employee), and brings back anything left over. This is how you have goods sold
*from a chest you choose* by someone you can watch do it; employees' own Selling settings still sell
straight from their deliveries.
A route can carry everything or only the items you pick, **leave behind** some of each at the source,
and **top up to** a level at the destination (keep a furnace room stocked with 128 coal). Routes take
turns; it picks up a load (as much as its backpack holds) and unloads it all at the destination.
Chests within `jobs.courier.walk-distance` (64 blocks) are walked to; farther ones, or other worlds,
it takes a shortcut to (a puff of smoke). `/chest` numbers work from anywhere. Route chests are kept
loaded. Only plain items (nothing renamed or enchanted) are carried. A courier has no storage of its
own.

### Homes
Every employee needs a home. Without one they're **homeless**: they still work, but unhappily and
at 75% speed (`homes.homeless-work-speed`). Their label says "homeless" and their menu shows it.

**From the NPC Homes catalog:** the **Home** button in their menu (or `/emp house <name>`) lists the
catalog's houses. Click one and it's placed where you stand as ghost blocks; **[Turn 90°]**,
**[Move here]**, then **[Build it here]**. It must be on land you may build on and not overlap
another employee's home. Build it following the ghosts: once the bed and door are in and
`homes.finished-percent` (90%) of it is built, they move in by themselves. The menu shows how far
along it is.

**Who builds it** (buttons in the Home menu, or in chat after placing):
- **You:** `/emp house <name> preview` shows the ghost blocks again; build over them.
- **A builder:** `/emp house <name> builder [builder]` gives one of your builders the house to build
  (materials from your storage, as with any build); it starts straight away.
- **Themself:** `/emp house <name> self on`. Every `homes.self-build-break-minutes` (5) they take a
  one-minute break to work on it, and they also work on it whenever their job has nothing to do:
  fetching materials from your storage, digging out what's in the way inside, and placing blocks
  bottom up. Slow, but free. If something's missing they tell you (event log). They never break
  chests or other containers, no-work zones or other people's land; guards and bodyguards stay on
  duty when there's trouble (and a following bodyguard doesn't go off to build). They move in at
  `homes.finished-percent` and keep going until the last block is on, then stop by themselves.
  Their home site is kept loaded while they build it.

**By hand:** build a bed with a door within 8 blocks, look at the bed and run `/emp house <name>`.

One bed per employee. Breaking the bed makes them homeless again; `/emp house <name> clear` (or
**Move out** in the Home menu) moves them out. Turn it all off with `homes.enabled: false`.

Each placed home is built from its own copy of the house's file (`blueprints/homes/sites/`), so
changing or removing the catalog house later doesn't affect homes already placed.

**Your own houses:** `/emp homes upload` (or **Upload your own house** in the Home menu) gives
you a link to an upload page; drop house files on it (each needs a bed and a door). They're yours
alone: they show under **My houses** in your employees' Home menus. `/emp homes mine` lists them,
`/emp homes forget <name>` deletes one; up to 20 each. They're kept in
`blueprints/homes/players/`.

**The upload page** is served by this plugin's own web server at `web.public-url` + `arc/upload/`
(e.g. https://your-domain.example/map/arc/upload/...), so no outside site is needed; the link from
the game is the sign-in and works for 15 minutes. `blueprints.upload-url` can still point at an
outside site instead. `/emp blueprints upload` uses the same page for ordinary blueprints.

**The catalog (admins):** `/emp homes upload catalog` gives an upload page whose houses go straight into the catalog;
`/emp homes add <blueprint> <id> [name]` copies a blueprint from the
blueprints folder into `blueprints/homes/` (it must have a bed and a door);
`/emp homes icon|style|describe|rename <id> ...` dress it up; `/emp homes remove <id>`;
`/emp homes list`. It's saved in `blueprints/homes/homes.yml`; house files dropped into that folder
by hand are listed too.

### Personalities
Every employee gets **one random personality trait** when hired; you don't choose it. It's shown in
their menu and when you hire them, and a fired villager keeps it if it's hired again. Every so
often (`personalities.calm-minutes`, 8 by default, between distractions) the trait takes over and
they drop their work for **a minute or two** and then go back to it:

| Trait | What they do |
|---|---|
| Absolutely loves sheep / Adores cows / Chicken whisperer / Pig fanatic / Cat person / Dog person / Horse lover / Bunny chaser | Follow that animal around when one comes within 12 blocks (hearts and all) |
| Loves to swim | Jump into nearby water (a pool of 9+ blocks, not an irrigation ditch) and swim around |
| Flower sniffer | Go from flower to flower smelling them |
| Campfire dreamer | Warm their hands at a lit campfire |
| Stargazer | Stop and stare at the sky on clear nights |
| Rain dancer | Dance when it rains |
| Sleepyhead | Lie down for a nap |
| Chatterbox | Walk over to a nearby employee or player and chat |
| Daydreamer | Wander off 14-25 blocks, lost in thought |

Distractions only start between tasks (never halfway through felling a tree). They end early if
the cause goes away (the sheep wanders off, the rain stops, it gets light). Guards and bodyguards
ignore their trait while monsters are within 16 blocks. Miners and quarry miners walk to a
distraction and never tunnel to one. Turn it all off with `personalities.enabled: false`.

### No-work zones
Every employee can have zones it leaves alone: no mining, chopping, harvesting, planting, building,
smelting at furnaces, ranching, trading, picking up or (guards) fighting inside them. Walking through
is fine, but a miner won't tunnel through one. A zone is either a WorldEdit selection given to one
employee (`/emp zone <name> add`), or a **preset**: a named zone saved once (`/emp presets save
<preset>` with a WorldEdit selection) and given to as many employees as you like (`/emp zone <name>
add <preset>`). Saving a preset again under the same name moves it for everyone who has it. In the
menus: **No-work zones** in an employee's menu lists its zones and presets, with buttons to add your
selection, pick or save a preset, show them, and remove them.

---

## Menus

`/emp` opens your employees, **one row per job**: the job's icon on the left, its employees' heads
after it. Click a job icon to see only that job. The bottom row has:

| Button | Does |
|---|---|
| Pause everyone / Put everyone back to work | `/emp pauseall` / `resumeall` |
| Put up a board | A status board (click) or sales board (right-click) on the wall you look at |
| Chests | Which `/chest` numbers your employees use, by job |
| Hire someone | Pick a job; hover a job for what it does and costs |
| Control panel | Link buttons and levers to actions |
| Merchant prices | Today's sell prices |

Clicking an employee opens their **card**: a short status line and buttons for the everyday things
(work area, storage, pantry, home, backpack, pause, bring them here, fire), plus a **Details** button
for what they're doing, their food, home, mood and working day. **More settings…** opens their full
menu: the job's own settings (filter, build, herd size, trades), no-work zones, supplies, skin,
rename, selling, recent problems and the rest. None of these teleport you to an employee - only
**Bring here** (or `/emp move`) moves them to you. Closing a backpack or chest you opened from a menu
takes you back to it. Anything that needs typing (a name, a number, a link) is asked for in chat;
type `cancel` to stop.

---

## Work areas

**Easiest: pick it from above.** On an employee's card click **Set work area** (or **Pick it from
above** in the Work area menu) and you leave your body: it stays standing where you were, and you
float invisibly over the employee's site, flying about (space up, shift down) for a bird's-eye view.
**Left-click** where you look to drop a corner; the outline stretches to wherever you look, with its
size shown, until you **left-click** again. Quarry miners and miners then take a depth: **scroll the
mouse wheel** (down is deeper) and left-click to confirm. **Right-click** (holding anything) goes back
a step; **F** (swap hands) cancels. Either way you snap back into your body. Storage and pantry chests
can be picked the same way (**Pick a chest from above** in the storage menu, **Pick from above** in
the pantry window): left-click the chest.

While out of your body you can't be hurt or hurt anything, pick things up, open anything or use
commands, other players can't see you, and you can't float more than 96 blocks from where you
started (nor through walls). After five minutes, on logging out, or when the server stops, you're
put back in your body; after a crash, when you next join.

Or by command:

| How | Command |
|---|---|
| Your WorldEdit wand selection | `/emp area <name> worldedit` |
| A square around you, at the job's usual depth and height | `/emp area <name> radius <r>` |
| Corners you stand on | `/emp area <name> pos1`, then `pos2` |
| Stretch down to bedrock (miner, quarry) | `/emp area <name> bedrock` |
| Move the employee and their area to you | `/emp move <name>` |
| Show the outline | `/emp area <name> show` |
| Go there (the middle, or a corner), with the outline | `/emp area <name> tp [middle\|nw\|ne\|sw\|se]` |

All of these are in the **Work area** menu too (shift-click **Work area** in an employee's menu to
teleport straight there). A flat selection is extended 7 blocks up. Moving an
area also moves the employee's home spot into it, and an employee far away is brought there. Work
sites are kept loaded with chunk tickets, so employees work, and crops and trees grow, while nobody
is around.

---

## Storage: where things go

Each employee delivers to exactly **one** place, so nothing is ever counted twice:

- a **VirtualChests chest** (`/emp storage <name> vc <n>`), reached remotely every 30 seconds,
- a **placed chest or barrel** it walks to (`/emp storage <name> chest`, looking at it), or
- its **backpack** only (`/emp storage <name> backpack`).

The storage menu has a picker for `/chest` numbers showing which other employees use each one.
`physical-chests.enabled` / `virtual-chests.enabled` turn a kind off server-wide.

### Chest groups
Chest numbers come in blocks of 50 per job, so you know what's where:

| Job | Chests | Job | Chests |
|---|---|---|---|
| Lumberjacks | 100-149 | Smelters | 350-399 |
| Farmers | 150-199 | Ranchers | 400-449 |
| Miners | 200-249 | Guards | 450-499 |
| Quarry | 250-299 | Bodyguards | 500-549 |
| Builders | 300-349 | Traders | 550-599 |
| | | Couriers | 600-649 |

Chests 1-99 are left for your own use. Both the size and where each group starts are in `config.yml`
(`virtual-chests.group-size`, `virtual-chests.groups`); changing them takes effect on `/emp reload` or a restart.

New employees take the first free (unused, empty) chest in their job's group. When a chest is full
the employee claims the next free one in the group as **overflow** (up to 10, `overflow-chests`) and
tells you. **Chests** in `/emp` shows every group; **Sort into groups** moves older chests into
their groups (items and all; chests holding your own things are never overwritten), and **Move this
group** moves a whole group to new numbers (e.g. 650-699) when it fills. Moves copy first and only
then empty the old chests, and refuse while a chest involved is open.

---

## Supplies: where materials come from

Builders, smelters, ranchers, lumberjacks and farmers take materials from their backpack first, then
from their **supply**: their own storage, any extra `/chest` numbers or chests you add
(`/emp supply <name> add <n>` / `add chest`) and, unless turned off (`/emp supply <name> team off`),
wherever your other employees deliver. Only plain items are taken (nothing renamed or enchanted).
VirtualChests are reached remotely; placed chests are walked to.

---

## Building blueprints

**Formats:** `.schem` (Sponge v1-v3, WorldEdit and most sites), `.schematic` (old MCEdit/Schematica,
needs WorldEdit), `.litematic` (Litematica, multi-region) and `.nbt` (vanilla structure blocks).
Block names from older Minecraft versions are upgraded through WorldEdit; blocks this server
doesn't know are skipped and listed; blocks with unknown properties are built without them.

**Space round the build:** many blueprints come with empty space round the outside. **Space round the
build** in the build menu (`/emp build <name> around clear|leave`) chooses: **dug out** (whatever is
there goes) or **left alone** (only space inside the build, its rooms and doorways, is cleared; your
ground and neighbouring builds are left be). Right-click it, or `/emp build <name> preview digging`, to
see what gets dug: red glass round the build, orange inside it.

**Builds in the ground** (a cellar, a hillside, extending a mine or hall): a builder first digs out
what the plan has as air, from the top down like a basement, then builds from the bottom up. To get
to its work it digs through its own site, but only blocks that are going anyway: where the plan has
air, or rock where a block isn't built yet. Never anything outside the plan or already finished. When that's not
enough (a floor under a finished room, a corner of natural stone in the way), it tunnels through
natural ground (stone, dirt...) in the site or within 3 blocks of it, keeps track of every block, and
puts each back exactly as it was once the build is done ("Filling in its way in"). A block it can't reach is skipped and retried
on a later pass; on its last look over the build, anything still out of reach is placed (or cleared)
from where it is, with a puff of particles, so a build always gets finished. `/emp debug <name>` logs what an employee tries and why it fails to the server console.

**Planning it yourself, in the world:** `/emp plan <builder>` (or **Plan it yourself** in the build
menu). The builder waits while you plan and builds only once you press Done (cancel, and nothing was touched).
Until `/emp plan done`, WorldEdit only plans: //set, //walls, //replace, //paste, brushes, //undo
and the rest go into the builder's plan as ghost blocks only you see; nothing in the world changes
(planned air means "clear this"). Blocks you place by hand are real (they use your items) and part of
the plan too, so you can build some yourself and leave the rest to the builder; breaking a ghost takes
it out of the plan. `/emp plan hand ghost` makes hand-placed blocks ghosts instead (free; breaking a
real block then plans clearing it), `/emp plan hand real` switches back. **Done** saves it as blueprint
`plan-<you>-<builder>.schem`, or name it: `/emp plan <builder> <name>` to start a named plan, `/emp plan
done <name>` (or **Done, and name it**) when finishing, `/emp build <builder> rename <name>` any time after. `/emp plan <builder> new [name]` (or right-click
**Plan it yourself**) starts a fresh plan; the old one stays in the blueprint folder. Blueprints made in the game (plans,
`/emp blueprints save`) remember where they were made: giving one to a builder puts it back there
(add `here`, `/emp build <builder> <name> here`, to put it where you stand, then shift, turn or move it as usual) and it's an ordinary build from there: materials, swaps, buying, Start.
**Changing a finished build without plan mode:** once a build is finished it keeps up with you. Blocks you
(or players you `/emp trust`) place, break or change with WorldEdit in it or up to 3 blocks round it become part of
its plan a couple of seconds later, so the builder never undoes them: not in repairs, not when checking it over,
not after a later plan edit. Building out to the side grows the build (the 3 blocks count from its new edge), so an
added wing or an elevator is part of it from then on. Your trader's job blocks and trapdoors in its stalls count too.
Explosions, fire and other players aren't taken in, so repair mode still puts those back. A downloaded blueprint
that's changed this way is saved as `<name>-as-built.schem` (the original file stays as it was). Plan mode is still
the way to give the builder something to build. Turn it off with **Keep up with my changes** in the build menu, or
`/emp build <builder> follow off`. `/emp build <builder> follow now` takes in changes made before that (every placed
block that differs from the plan; gaps aren't taken in, since they could be creeper holes).
`/emp plan <builder>` again carries on with the same plan, even while it's being built; `/emp plan
cancel` puts back how it was. WorldEdit's undo history is cleared when you stop, so a later //undo
can't place planned blocks for real.

**Saving something you built:** select it with the WorldEdit wand and `/emp blueprints save <name>`
(air included, so a builder clears what was air). Builds saved in ArcShop's drafting studio
(`/studio save`) and WorldEdit's schematics folder show up as blueprints too.

**Getting files:**
- **From your computer (easiest):** `/emp blueprints upload [name]` (or the build menu's **Upload**
  button) gives you a clickable link to the upload page (served by this plugin). Drop files on it (or pick
  them), and each one shows up in game and on the page a second later. The link works for 15
  minutes and takes any number of files. Use this for Planet Minecraft, Wormhole and other sites
  where you have to press a download button in the browser: download it, then drop it here.
- **From a link:** `/emp blueprints download <link> [name]` (or the **Download** button). Dropbox,
  Google Drive and GitHub share links are turned into file links. A link to a web page is searched
  for a link to a schematic or `.zip`, like a download button's. Sites behind Cloudflare's bot check
  (Planet Minecraft) and sites that decrypt in the browser (Wormhole) can't be fetched by a server;
  you're told to upload instead.
- **Directly:** put files in `plugins/ArcEmployees/blueprints/`. WorldEdit's `schematics` folder is
  read too, so `//schem save` works.

How uploads work: the command writes a ticket to `plugins/ArcEmployees/uploads/<token>/ticket.json`.
The plugin's web server serves the page at `web.public-url` + `arc/upload/<token>`, takes each file
(up to `blueprints.max-download-mb`) and puts it in `<token>/in/`. The plugin checks that folder
every second, files it like a download, and writes `results.json` for the page. Tickets are deleted
when they run out and on restart. `blueprints.upload-url`, if set, sends players to an outside page
that reads the same folders instead; empty (the default) uses the built-in one. Uploading needs the
web server (`web.enabled`).

**Placing and building:**
1. Stand where it should go: `/emp build <name> <blueprint>` (or **Build → Choose a blueprint**). The
   bottom centre goes at your feet, and you see ghost blocks only you can see. Ghost blocks melt
   away within about a block of you and come back behind you, so you can walk and fly through the
   preview without getting stuck. The preview stays while you're within 32 blocks of the build and
   goes `preview-seconds` (60) after you leave; `preview off` (or right-click **Preview**) hides it.

   **Blocks this server doesn't have:** a blueprint made in a newer Minecraft (or with mods) can use
   blocks this version doesn't know (poplar wood, say). You're told when you place it, and they're
   listed first in **Materials and swaps** (shown as structure voids). They're left out until you
   pick what to build instead: click one and choose a block (facing and shape carry over), or
   **Use the closest match for each** (`/emp build <name> swap unknown auto`) picks the same kind of
   block in the wood or stone you have most of. `/emp build <name> swap poplar_trapdoor spruce_trapdoor`
   does one by hand. (.schem files are read by the plugin itself so these blocks aren't lost;
   WorldEdit would turn them into air.)

   **Ground under it:** many schematics are saved standing on a slab of dirt and grass. The bottom
   layers that are mostly ground (dirt, grass, sand, stone...) are found automatically: the ground
   blocks in them, and grass and flowers growing on them, are left out, the build is sunk so that
   ground lines up with yours, and air in those layers isn't dug out. Anything else in them (a path,
   a foundation) is still built. `/emp build <name> ground keep` builds the ground too (`skip` leaves
   it out again).
2. Line it up: **Turn 90°**, **Mirror**, **Shift** (x y z), **Move here**, **Preview**. Turning is
   only allowed before starting (or after it's finished), so nothing ever has to be redone.
   **Move here** (`/emp build <name> move`, or right-click **Build site** in its menu) works in any
   world and at any time: try a build out in the studio, then stand on the real spot and move it
   there. Swaps, left-out blocks, the ground setting and bought materials come along, and so does the
   builder. A started build asks first; what's built in the old spot stays, and it starts over at
   the new one when you press Start.
3. **Materials and swaps** (`/emp build <name> materials`; `materials chat` lists it in chat) shows,
   for each block: how many are still to place, how many are in storage, how many it will craft,
   and what's missing, with how to get it (buy it, buy what it's crafted from, or swap it).
   - **Swap a block:** click an item to build it from something else: similar blocks come first
     (planks for planks, stairs for stairs), the ones you have enough of at the top. Or hold a block
     and click **Use the block in your hand**, **Click a block in the world** (any block, near or far),
     **Search** by name, or **Leave out all** of that block. Opened by clicking a block in the
     build, it also has **Leave out just this block**: then keep clicking blocks to leave those out
     too (`/emp build <name> skip <x> <y> <z> [back]`, `skip reset` puts them all back). Facing, half and shape carry over where the new block
     has them, and wall torches, signs and heads stay on the wall. **Click a block in the build**
     (`/emp build <name> pick`) shows the preview: look at any ghost block and click it to swap what
     it's made of. In chat: `/emp build <name> swap <block> <new block | none | back>`, `swap reset`,
     `swaps` to list them. Swapping during a build swaps blocks already placed too, and the builder
     looks the build over again straight away (swap a block it's stuck on for one it has).
   - **Swaps and left out** (in the materials menu) lists every swap and left-out block: click one to
     bring it back as drawn, right-click to pick something else, without touching the rest.
   - **Crafting:** blocks storage doesn't have are crafted from spare materials (logs into planks
     and stairs, stone into bricks...), up to five steps deep. That includes the stonecutter (one block a
     stair) and smelting (cobblestone into stone into smooth stone, sand into glass, raw iron into ingots),
     which takes a few seconds a stack and fuel: coal or charcoal (8 items each), or spare logs or planks
     (1.5 each), or charcoal it smelts from logs. If it can't, its status says why (nothing spare to make it from, or
     the ingredients couldn't be fetched), and it moves on to the next block rather than getting stuck. "Spare" means what's in its
     backpack, storage and bought stock beyond what the build itself still needs, so it never eats
     its own blocks. The list shows what it can craft. It crafts at the end of a pass (fetching the
     ingredients first) and then places the result. **Craft missing blocks** in the build menu or
     `/emp craft <name> on|off` turns it off; `/emp craft <name> never add <item>` keeps an item
     from ever being crafted with.
   - **Buy what's missing** prices what's still short after crafting at the merchants' buy price
     (ArcShop's catalog; undiscovered items can't be bought, as in /shop). Blocks merchants don't
     sell are crafted from ones they do (it buys the logs for spruce stairs). Leaves, flowers,
     saplings, bushes (firefly bush...), vines, moss, grass, ferns and other plants nobody can craft or
     get from /shop have their own prices (`market.build-prices`, 2.00 each by default). Anything else
     nothing lists and nobody can craft from what's sold (leather, flint, a bookshelf's books...) costs
     `market.build-fallback-price` (2.00) for builds; ores, netherite, treasure and boss items are
     never sold. Crafting for builds goes up to five steps deep (a lectern from sugar cane and leather
     is four). Something
     /shop sells but you haven't discovered can be unlocked by an admin:
     `/emp unlock <player> <item> [item...]` (and `/emp lock <player> <item>` takes it away again).
     This changes ArcShop's own discoveries, so /shop sells it too. After you confirm, it pays from your balance and
     hands the items straight to the builder for this build (nothing has to fit in storage). What it
     doesn't use is refunded when the build is finished, cancelled or replaced, or the builder is
     fired; right-click the button (or `/emp build <name> buy undo`) returns them now.
     `/emp build <name> buy` shows the bill in chat.
4. **Start building.** It clears what's in the way where the blueprint has air (unless
   `clear off`), places solid blocks, then things that hang on them, layer by layer, without block
   updates (like a WorldEdit paste). It moves like a player on scaffolding. Missing materials are
   skipped and retried every 30 seconds; when every layer is done it checks the build once more.
   After a restart (or any change) it carries on from the layer it was on, then checks every layer
   again before calling it finished.
5. **Take it down** (`/emp build <name> dismantle`): the builder breaks what it built, top layer
   first, and takes the blocks themselves back to storage. Only blocks that are still what the
   blueprint has there come down, so things added since stay, and so do chests with something in
   them. What it dug out to make room isn't put back (CoreProtect can, afterwards).
6. **Cancel** stops the build: what's built stays, and the leftover materials go straight back to its
   storage (blocks it dug up while clearing too). To take the build down as well, roll its changes
   back with CoreProtect: `/co rollback u:#<name> t:<time>`.

Builders don't craft: planks, stairs and so on must be in storage.

**Repair mode:** once a build is finished, a builder can look after it: every
`jobs.builder.repair-check-seconds` (60) it looks the build over and puts back blocks that have gone
missing (creeper holes, fire, griefing), from your storage, then tells you how many it fixed. Repairs
only fill gaps: nothing is cleared or swapped, so chests and furniture you've added since stay, and
grass, crops, saplings and leaves are left alone. Missing materials don't hold it up: it notes what it
couldn't put back and tries again next time. Turn it on with **Build → Repair mode** or
`/emp repair <name> on`.

To look after something that's already built (your base, a farm, a wall), select it with `//wand`
and use **Build → Look after an existing build** or `/emp repair <name> snapshot`: the selection is
saved as it is now (`blueprints/repair-<name>.schem`) and the builder repairs it from then on. After
changing it on purpose, take the snapshot again, or the builder will put the old blocks back.

**Size limits:** a blueprint can be up to `jobs.builder.max-volume` blocks (2,000,000 counting air:
e.g. 200x50x200); files over 100 MB are refused, and `.nbt` files (which load on the main thread)
over 4 MB. Files are read in the background and built one block at a time, so even the biggest
allowed build never freezes the server. (That's unlike WorldEdit's `//paste`, which places the whole
thing in one tick: with WorldEdit's `max-blocks-changed: -1` a huge paste can stall or crash a server.)

---

### Trading hall stalls (traders)

`/emp stalls <trader>` lists them and marks them in the world for a minute and a half, only for you
(`/emp stalls <trader> show`, `show off`): a label over each stall (its number, job, the villager in it and the
books it sells, what it's after), green sparks round the villager's spot, orange round the job block's, blue round
the trapdoor's.

A stall is a pod in your trading hall for one villager. `/emp stalls <trader> add [job]` (e.g. `add
librarian`), then three clicks, each like placing a block (the spot is next to the face you click):
the top of the floor block the villager stands on; where its job block goes (the top of the block it
sits on, or the job block itself if it's there; the floor again for none); where the trapdoor goes
under the roof (the underside of the roof block; the floor again for none). The trader then:
- **brings a villager:** for an empty stall, the nearest grown-up villager within 48 blocks that's
  jobless or already the stall's job and not working at a workstation of its own (named and leashed
  ones, nitwits, ones on other people's land are left alone). It walks over, puts it on a lead and
  walks it back on foot; a villager stuck in a hole on the way is pulled out. At the stall it takes
  the trapdoor off if it's there, and the villager walks in by itself while it waits by the way in;
  never teleported in;
- **shuts it in:** the moment it's in, puts the job block down (usually in the doorway) and the
  trapdoor above, from its storage, or crafted from what's in storage. It won't fetch a villager for a
  stall till it has them (it tells you if it can't get them);
- **gives it the job:** the villager is pointed at its stall's job block and takes that job itself, the
  game's own way (if nobody has traded with it yet), rather than claiming one farther off. The job
  block is the stall's job (`add librarian`, `job <stall> librarian`), or the villager's own job;
- **rerolls trades:** `/emp stalls <trader> want <stall> mending` (or `protection 4`, `emerald`...) until
  one sells it (up to 400 tries), then it tells you. Trade once to lock it in. `reroll <stall>` once now.

`/emp stalls <trader> move [radius]`, then click a spot: it walks the villagers within that many blocks
of you (8 by default; not named ones or ones in stalls) there, one at a time, pulling one along only if
it's stuck. `job <stall> <job>` sets a stall's job; `/emp stalls <trader>` lists them.

### Who sells what: /trades
`/trades` opens every villager with trades within 64 blocks: an icon each (its job block; glowing if it
sells enchanted books), its level, its stall and trader if it's in one, and every trade with its price (sold-out ones
struck through). Click one and `/guide` walks you to it. `/trades mending` (any part of an item or enchantment
name, tab completes) lists the villagers that sell it with a **[Go]** button each, and marks them with green
sparks for a minute. `/trades labels` puts each nearby villager's best trades (books first) over its head, only
for you, until you turn it off. Each `/trades` also writes `reports/trades-<player>.txt`.

## Selling and the market

### Merchants
An admin places a merchant NPC in your shop building: `/emp market place [name]`. Players right-click
the merchant to open a sell window: put items in, see the total, click Sell (items it doesn't buy
come back). **Buy from** opens the merchant's goods: ArcShop's catalog by category at ArcShop's
prices, with ArcShop's discovery rule (you can only buy what you've discovered, read from ArcShop's
database). Click buys 1, right-click 16, shift-click a stack.

With `market.merchants-only: true` (the default) all trading goes through merchants: `/shop`
(ArcShop) and `/sell` (EssentialsX) are blocked and tell players where the merchants are (or open the
shop when one is near). Set it to `false` to allow them again; `market.blocked-commands` lists what's
blocked. Admins shift-right-click a merchant (or `/emp market edit` nearby) to rename, move or
reskin them. `/emp market prices` (or the menu's Merchant prices) shows today's prices.

### Prices
- Merchants pay **25% of ArcShop's price** (`plugins/ArcShop/catalog.yml`) for the ~90 items ArcShop
  lists, so buying to resell never pays; change ArcShop's price and both buying and selling follow.
  Things ArcShop doesn't list (mob drops, wool...) have small prices in `market.prices`.
- **Demand:** an item's price halves for every 256 of it you sell, recovering over about a day.
- **Daily limit:** you plus your employees earn full price for the first **$300 in any 24 hours**
  (`max-income-per-day`). Past the limit, merchants still buy
  from *you* at **25%** of the price (`over-limit-rate`; 0 makes it a hard stop): you can always raise
  cash in a pinch, but grinding past the limit pays poorly. Employees stop selling at the limit and
  store their goods instead, so nothing gets sold off cheaply without you deciding to. A workout pays
  $60. The sell window shows how much full-price room you have left.
- **Real-world markets** (optional, `market.real-world.enabled`): items follow real commodity prices
  from Yahoo Finance: gold, US steel (iron), copper, platinum (diamonds), silver (quartz), bitcoin
  (emeralds), crude oil (coal), lumber (logs), wheat, corn (vegetables), live cattle (beef, leather),
  lean hogs (pork), sugar, cocoa and cotton (wool). Only the movement counts: price × (today ÷ its
  3-month average)³, between half and double, for buying and selling alike. Fetched automatically
  every `refresh-minutes` (60); switching it on or off takes `/emp reload`.

### Employees selling
An employee can sell what it makes instead of storing it, whenever it delivers (every 30 seconds to a
`/chest`), as long as a merchant exists. Set it with the employee's **Selling** button or
`/emp sell <name> add <item...> [rule]`, or `/emp sell <name> all [rule]` for everything merchants buy.
A rule can combine:

| Part | Means |
|---|---|
| `keep 256` | Keep 256 in storage first; the rest is sold (storage above 256 is sold down too) |
| `sell 50%` | Sell half of what's left over (`keep 50%` means the same) |
| `sell 256` | Sell at most 256 per 24 hours |
| *(nothing)* | Sell all of it |

**Never sell:** items on an employee's never-sell list are always kept, even with *sell everything*
on, so "sell everything except saplings and diamonds" is two clicks. In the **Selling** menu, click an item under *Sells* to change its stock, share and daily limit or
take it off (or move it to *Never sells*); click one under *Never sells* to take it off. *Add
items to sell* and *Add items to never sell* open a picker showing what the employee has, with a
**Search** button (type `spruce`, click the sapling): no item names to remember.
By command: `/emp sell <name> never <item...>`, and `remove <item>` takes an item off either list.

Examples: `/emp sell Lumbo add oak_log spruce_log keep 256 sell 50%`, `/emp sell Bea add wheat sell 25%`,
`/emp sell Ned all keep 64`. Anything over the daily limit is simply stored (you can still sell it
yourself at the over-limit rate). Each employee's menu
shows what it sold in the last 24 hours, all time, and its latest sales; the
[sales board](#control-boards-and-the-control-panel) shows everyone's.

---

## Food, the working day and status

**Food instead of wages.** Employees aren't paid. Each keeps a small food store and eats
`food.points-per-day` (11) food points every Minecraft day: an item's food points are its nutrition
plus saturation, as for a player, so a bread (11) is about a day and a cooked steak (20.8) nearly two.
When the store drops under a day's worth they take enough for three days from their **pantry**: the
chest you set with **Pantry** on their card (or `/emp pantry <name> chest` looking at it,
`/emp pantry <name> vc <n>` for a /chest), otherwise wherever they deliver. With nothing left at all
they eat anything edible in their backpack. Out of food, they stop and show **⚠ Too hungry to work**
until there's food again. They never eat rotten flesh, spider eyes, golden apples and the like
(`food.never-eat`). Paused and resting employees eat nothing. `/emp pantry <name>` shows how many
days they have.

**The working day** (`schedule`): they work from 6:00 to 18:00 on the Minecraft clock, relax in the
evening (their own pastime, a campfire, a chat, a stroll) and sleep from 21:00: in their home's bed if
they have one and can get there, otherwise where they are. Guards and bodyguards are on duty day and
night (`schedule.night-shift-jobs`). `schedule.enabled: false` has everyone work around the clock.

**Mood:** an employee without a home is unhappy and works slower (`homes.homeless-work-speed`). The
card shows their mood and why.

**Status over their heads:** under each employee's name is one line saying what they're doing right
now, with a symbol and a colour: ⚒ working, ☾ sleeping, ☕ relaxing, ❤ their personality got the better
of them, ⌛ idle, ✋ paused, ⚠ trouble (hungry, chest full, stuck). "· unhappy: no home" is added while
they're homeless. The same line heads their card, the `/emp` list (where anyone in trouble is listed
first, under **Needs attention**) and the status board.

**Nobody online:** employees rest while nobody is on the server (`rest-when-server-empty`),
showing "Resting" until someone joins. To keep one working while everyone's away, authorize it in
its menu (**Rests/Works while nobody's on**) or with `/emp away <name> work` (`rest` undoes it).
It eats while it works, so keep its pantry stocked.

| Job | Asks for (plus food) | Job | Asks for (plus food) |
|---|---|---|---|
| Lumberjack | iron axe | Builder | iron pickaxe, iron shovel |
| Farmer | iron hoe | Smelter | furnace |
| Miner | iron pickaxe | Rancher | 2 leads, shears |
| Quarry | iron pickaxe, iron shovel | Guard | iron sword |
| Trader | book and quill | Bodyguard | iron sword, shield |
| Courier | bundle | | |

---

## Managers: letting a friend run your employees

`/emp trust <player>` lets a friend manage all your employees: open their menus, change work areas,
routes, builds, selling and zones, pause, move or fire them. They open your list with `/emp manage <you>` (or **Friends' employees** in their own `/emp`
menu), and can use any of your employees' names in commands (`/emp area YourName:Ned show`, or just
`Ned` if they have no employee called that). `/emp trust` lists who can; `/emp untrust <player>` stops it.

---

## Reports and profit

**While you were away:** when you join after at least `report.min-away-minutes` (30), chat shows
what each employee did since you left: its work ("felled 40 trees", "mined 340 blocks (12 ores)"),
the top things it made, what it sold, and its profit, then everyone's totals and anything that needs
attention. Hover over a line for the money breakdown. Employees with nothing to report (resting, as
they do while nobody's on) are listed on one line. `/emp report [hours]` shows the same for any span
(24 hours by default).

**Profit per employee:** `/emp profit [days]` (7 by default) lists every employee, best first; the
business board shows the last 24 hours. Profit is

> goods put in storage + sales − materials taken from storage

with every item valued at what a merchant pays for it today, so it reads the same whether an employee
stores or sells. A courier that sells goes on the books for the stock it took from storage (already
credited to whoever made it) and the money it got, so its profit is what selling gained or lost:
selling a lot of one thing at once fetches less than full price, because the price falls as you
flood the market. Moving goods between employees cancels out: a miner is credited with the raw iron,
the smelter with what smelting adds. An employee that costs more than it brings in over a week is
flagged; builders, guards, bodyguards and couriers are marked instead, since their worth (a build,
safety, logistics) isn't in goods. The books are kept for 30 days in `ledger.yml`.

---

## CoreProtect logging

Employees change blocks directly, without the block events CoreProtect listens to, so on their own
their work wouldn't show up. With CoreProtect installed (and `coreprotect.enabled`), every block an
employee breaks or places, and every chest or furnace it takes from or puts into, is logged under
`#` + its name (`coreprotect.user-prefix`). `/co inspect` shows "#Ned" on a block he mined, and
`/co rollback u:#Ned t:2h r:50` undoes his last two hours nearby: every block, ores included, comes
back. What he took out of or put into placed chests is rolled back too, but items already delivered
to a `/chest` (VirtualChests) stay, since CoreProtect can't see those: empty them by hand after a
rollback, or you'll have the ores twice. Crops a farmer harvests and
replants in one go aren't logged (the block never really changes).

---

## Control boards and the control panel

### Avatars
Life-size stand-ins of your employees for a control room: each wears its employee's skin, holds its
tool and shows its live status under its name (the same line as over the employee's head). **Right-click** an avatar for that employee's menu; **shift-right-click** to pause or
resume them. `/emp avatar <name>` (or the armor-stand button in their menu) puts theirs where you
stand, facing the way you face; `/emp avatars` lines up everyone in a row to your right, facing you
(also in the control panel). `/emp avatar <name> remove`, `/emp avatars remove`.

### Boards
Big text displays for a control room, refreshed every two seconds:

- **Status board** (`/emp board`): every employee by job with their status symbol and what they're doing,
  builder and quarry progress, sales today, who needs attention, and the latest problems and events.
- **Sales board** (`/emp board sales`): today's earnings against the limit, each employee's sales
  (last 24 hours and all time), the latest sales, and real-world market moves.
- **Business board** (`/emp board business`): your balance, what each employee costs per day against
  what it sold in the last 24 hours (and the net), totals, today's top-earning items and the best
  prices right now.
- **Employee board** (`/emp board employee <name>`, or the sign in their menu): one employee in full:
  status, area, storage, what they sell and keep, their sales and their recent events.

Look at a wall to put a board flat on it, its top edge at the top of the block you look at; it hangs
down from there as it fills, so it never grows into the ceiling. Look at nothing and it stands free
and turns to face you. (Boards put up before this hung upward; `/emp board move` re-hangs one.)

| To... | Type |
|---|---|
| Put one up | `/emp board`, `board sales`, `board business` or `board employee <name>`, optionally with a size: `/emp board sales 4` |
| Make the nearest one bigger or smaller (text size) | `/emp board size <1-20>` (2 = a normal wall board) |
| Fit more on each line | `/emp board width <letters a line>` (105 to start; try 150) |
| Bring your nearest board to where you look | `/emp board move` |
| Take one down | `/emp board remove` (stand near it) |

Size and width are separate: size scales everything, width is how many letters fit on a line before
it wraps. A big board for across the room might be `size 5`, `width 150`.

### Control panel (buttons and levers)
**Control panel** in `/emp` (or `/emp link <action>`): pick an action, then right-click a button,
lever or any block. Actions: pause/resume everyone, pause everyone, resume everyone, pause/resume one
employee, open the employees, chests or price menus, list everyone, or any `/emp` command. A **lever**
linked to pause/resume follows its position: on = working, off = paused. Only you (and admins) can use
your controls. Break the block, or **Unlink**, to remove one.

---

## Sorting chests: /sort

`/sort` sorts the chests, trapped chests and barrels round you (8 blocks, up to 16; or your WorldEdit
selection with `sel`) and any of your virtual chests, by category. It replaces ArcShop's /sort (that one
is still there as `/arcshop:sort`); chests you'd given categories with ArcShop come over as kept chests.

- **`/sort`** opens the menu: sort, put your things away, tidy, undo, how far it looks, virtual chests,
  and every category.
- **Sorting shows the plan first** (`/sort organize [sel]`): each category with the chests it goes into
  ("Food → chest 4 north, 2 east (-339 67 186), virtual chest 12"), and a label floats over every chest
  for a minute naming what it'll hold (only you see them). **[Sort it]** (`/sort confirm`) moves it all
  in one go, after checking nothing changed meanwhile and nothing would be lost; **Undo** puts it back.
- **Categories:** eleven main ones (tools, armor, food, ores, wood, stone, nature, redstone, decoration,
  drops, everything else) are on. Turn one off and its items go to "everything else". Extras are off:
  wool, glass, concrete, dyes, crops, logs only, cobblestone and deepslate, brewing, enchanting, music
  discs, valuables; turned on, they take their items from the main ones (more, smaller groups when you
  have a lot). **New category** makes your own: name it, add items from your hand or a whole chest.
- **Keeping chests for a category:** in a category's menu, **Keep a chest for it** (click the chest) or
  **Keep a virtual chest for it** (its number); `/sort pin <category> [vc <n>]`, `/sort unpin`. Sorting
  fills kept chests first, then gives each category the chests already holding most of it, and lets the
  small ones share when chests run out. Kept chests count from up to 48 blocks away.
- **Labels** (`/sort labels`, or the sign in the menu): every chest and barrel within 8 blocks gets a
  small label on every side you can see (the front below the latch, the ends and the back; on top
  for a barrel facing up, or a chest boxed in on all sides) with
  what it's kept for or mostly holds, its two biggest stacks and how full it is. Only you see them,
  and they stay on until you turn them off. After `/sort organize` they show the plan for a minute.
- **Virtual chests:** add them to sorting in the menu or `/sort virtual add <n>`; they're sorted along
  with the chests round you. Careful with your employees' storage chests: items moved out of them are
  no longer in that employee's supply unless they land in another employee's storage.
- **Put away** (`/sort deposit [hotbar]`): your inventory (not the hotbar unless you say) into chests
  that have the same items, then chests for their category. **Tidy** (`/sort tidy`): merges and orders
  stacks inside each chest. Both can be undone (undo lasts until a restart). Chests someone has open,
  and land you can't use (ArcEstate), are left alone.

## Walking to a warp: /guide

`/guide <warp>` points the way instead of teleporting: an arrow and the distance in the action bar
(turning as you do) and a trail of gold sparks along a walkable path about 30 blocks ahead, worked out
again as you go. It stops when you arrive; `/guide <warp>` again or `/guide off` stops it sooner.
`/guide` lists EssentialsX's warps to click. It also takes coordinates (`/guide 100 -250` or `x y z`)
or one of your employees' names.

## The web map and build planner: /map
BlueMap's 3D map of the world, at `web.public-url` (e.g. **https://your-domain.example/map/**), with a 3D planner for your builders.

**Without nginx:** the plugin's web server can also be reached directly: set `web.bind: 0.0.0.0`,
open `web.port` (8101) on the host, and set `web.public-url` to `http://<your address>:8101/`.
The upload page then works on that port. The map and 3D planner are built to sit next to
BlueMap behind nginx (`deploy/map-nginx.conf`), so set that up for them.

- **Signing in:** type `/map` in the game and click the link (it works once, for 10 minutes, and signs that
  browser in for 30 days). `/map planner` opens the planner where you stand; `/map logout` signs you out of
  every browser. Only signed-in players see the map.
- **On the map:** three layers you can switch in BlueMap's menu: **Building plans** (each builder's build as a
  box you can see through the ground: orange building, green finished, blue waiting; click one to open it in
  the planner), **Villager trades** (every villager with trades: books in the label, all trades, its stall and
  whether it's locked when you click it) and **Employees** (where each is and what it's doing). A **Plan builds
  here** button opens the planner where you're looking, or on the last block you clicked.
- **The planner** (`/map/arc/editor`): the world round a spot, live (Load around here, 16 to 48 blocks), with a
  slider to cut it down to one floor and the ground hidden or shown. Pick a builder, then **Place** or **Break**
  blocks; your changes save on the server as you go (one draft per builder, there next time). Blue outlines
  show what the builder still has to build. **Send to <builder>** puts your changes in its plan the way plan
  mode does (only those blocks; nothing else is touched), joins fences, panes and walls up, turns ladders and
  gates to fit and gives doors their top half, and it starts building (or waits for Start, untick the box).
  Downloaded blueprints aren't changed: the build goes on as `<name>-as-built.schem`.
- **Villagers** tab: every villager in the area, its trades, level and stall; search for a trade and they light up.
- **Upgrades** tab: ideas left in `web/suggestions/*.json` (a list of `{id, title, why, kind: build|task|ok,
  world, edits: {"x,y,z": "block[state]"}, commands: [...], for: player}`), each one previewed in amber and
  added to your changes with a click. Claude writes these when you ask it to look at a build.
- **Setup:** the planner listens on `web.bind`:`web.port` (127.0.0.1:8101); nginx puts BlueMap (127.0.0.1:8100,
  `ip: "127.0.0.1"` in BlueMap's webserver.conf) at /map/ and the planner at /map/arc/, letting a request through
  to BlueMap only with a planner session. To set it up, copy `deploy/map-nginx.conf` to
  `/etc/nginx/snippets/arc-map.conf`, add `include /etc/nginx/snippets/arc-map.conf;` inside your site's
  HTTPS `server { }` block, check it with `sudo nginx -t`, and reload nginx. Undo: remove the include line.

## All commands

`<name>` is one of your employees (admins can use `Owner:Name`).

| Command | |
|---|---|
| `/emp` | The employees menu |
| `/emp hire <job> [name]` | Hire the villager you're looking at (or shift-right-click it); with no villager, admins hire someone where they stand |
| `/emp list` · `/emp jobs` · `/emp info <name>` | Who works for you, what jobs exist, one employee's details |
| `/emp area <name> worldedit \| radius <r> \| pos1 \| pos2 \| bedrock \| show \| tp [nw\|ne\|sw\|se]` | Work area; `tp` takes you to the middle or a corner |
| `/emp move <name>` | Bring them (and their area) to you |
| `/emp storage <name> vc <n> \| chest \| backpack` | Where they deliver |
| `/emp supply <name> add <n> \| add chest \| remove <n> \| team on\|off` | Where they get materials |
| `/emp filter <name> add\|remove <block...> \| clear \| reset` | Miner: looks for · smelter: smelts · quarry: throws away (names, `#tags`, `ores`) |
| `/emp herd <name> <n>` | Rancher's animals per kind |
| `/emp zone <name> add [preset] \| remove <preset or number> \| show \| clear` | No-work zones (no preset: your WorldEdit selection) |
| `/emp presets [list] \| save <preset> \| delete <preset> \| show <preset>` | Named zones to give to employees |
| `/emp build <name> <blueprint> \| start \| preview \| rotate [90\|180\|270] \| flip \| move \| shift forward\|back\|left\|right\|up\|down [n] \| shift <x> <y> <z> \| materials \| clear on\|off \| cancel \| info` | Builders (rotate, flip and shift only while the preview is showing; shift directions are as you're facing; **Shift it** in the build menu has arrow buttons) |
| `/emp build <name> swap <block> <new \| none \| back> \| swap reset \| swaps \| pick \| skip <x> <y> <z> [back] \| skip reset \| buy [undo] \| ground keep\|skip \| preview off` | Swap or leave out blocks, buy what's missing, the ground under it |
| `/emp build <name> follow on\|off\|now` | Whether a finished build takes in your own changes (on by default) |
| `/emp blueprints [upload [name] \| download <link> [name]]` | List, upload or download blueprints |
| `/map [planner \| logout]` | Sign in to the web map and the 3D build planner |
| `/trades [item \| labels on\|off]` | Villagers near you and what they sell |
| `/emp sell <name> add\|remove <item...> [keep <n>] [sell <n>%] [sell <n>]` · `never <item...>` · `all [rule]` · `off` | Employee selling |
| `/emp market [prices]` · `market place [name]` · `market edit` · `market remove` | Sell at a nearby merchant · admin merchant tools |
| `/emp market limit [amount]` · `market overlimit [percent]` | See or (admins) change the daily selling limit and the over-limit rate |
| `/emp chests [organize \| move <job> <first number>]` | Chest groups |
| `/emp board [status\|sales\|business\|employee <name>] [size] \| size <n> \| width <letters> \| move \| remove` | Boards |
| `/emp link <action>` · `/emp unlink` | Buttons and levers |
| `/emp pause\|resume\|toggle <name>` · `/emp pauseall \| resumeall \| toggleall` | Pausing |
| `/emp follow <bodyguard>` · `/emp stay <bodyguard>` | Bodyguard follows you / guards its post |
| `/emp away <name> work \| rest` | Keep working while nobody's online, or rest (the default) |
| `/emp trades <trader>` | Pick the villager trades a trader makes |
| `/emp craft <trader> on \| off \| never [add\|remove <item>]` | Trader crafting, and what it never crafts with |
| `/emp route <courier> add <from> <to> [items...] [keep <n>] [stock <n>] \| remove <n> \| clear \| list` | Courier routes; from and to: a `/chest` number, `look` (the container you look at) or `x,y,z` |
| `/emp repair <builder> on \| off \| snapshot` | Keep a finished build repaired; `snapshot` saves your WorldEdit selection as the build to look after |
| `/emp report [hours]` · `/emp profit [days]` | What your employees did; each one's profit |
| `/emp trust \| untrust <player>` · `/emp manage [owner]` | Let a friend manage your employees · manage a friend's |
| `/emp roam <miner> on \| off \| back` | Miner moves on when mined out / stays / goes back to its first area |
| `/emp avatar <name> [remove]` · `/emp avatars [remove]` | Avatars for your control room |
| `/emp skin <name> <player> \| default [skin] [slim] \| texture <ns:path>` | Looks |
| `/emp rename <name> <new>` · `/emp pantry <name> [chest | vc <n> | storage]` · `/emp fire <name>` | |
| `/emp all` · `/emp reload` | Admin: everyone's employees · reload config.yml |
| `/emp config <setting> [value]` | Admin: see or change any config.yml setting in game (saved and applied at once; tab completes the names) |

Permissions: `employees.use` (everyone), `employees.admin` (ops: anyone's employees, merchants,
reload, no employee limit), `employees.blueprints.download` (ops).

---

## Configuration

`plugins/ArcEmployees/config.yml`; apply changes with `/emp reload`, or change a setting in game
with `/emp config <setting> <value>` (e.g. `/emp config market.max-income-per-day 500`). New settings are added to an
existing file automatically on startup. The main knobs:

| Setting | Default | |
|---|---|---|
| `max-employees-per-player` | 6 | Admins aren't limited |
| `villager-hiring.villagers-only`, `natural-only`, `release-on-fire` | true, true, true | See [Hiring villagers](#hiring-villagers) |
| `villager-hiring.replenish.enabled`, `delay-minutes`, `radius`, `max-villagers-nearby` | true, 20, 48, 30 | Baby villagers replacing hired ones |
| `keep-work-sites-loaded` | true | Chunk tickets on work areas |
| `walk-speed` | 0.2 | Blocks per tick (a player walks ~0.216) |
| `rest-when-server-empty` | true | Employees rest while nobody's online (unless told to work away) |
| `food.points-per-day`, `top-up-below-days`, `top-up-to-days`, `never-eat` | 11, 1, 3, rotten flesh... | See [Food](#food-the-working-day-and-status) |
| `hiring.food`, `food-amount`, `food-extra-per-employee`, `food-max` | BREAD, 16, 2, 64 | Food a villager asks for to be hired |
| `hiring.reserve-days`, `max-reserved` | 7, 3 | Reserving villagers you can't hire yet |
| `schedule.enabled`, `work-start`, `work-end`, `sleep-start`, `night-shift-jobs` | true, 6, 18, 21, guard and bodyguard | The working day (Minecraft clock hours) |
| `jobs.trader.never-craft-with` | diamonds, netherite... | What new traders never craft with |
| `jobs.<job>.hire-items`, `tool`, `work-speed` | | Per job; `hire-items` is what a villager asks for (`[IRON_HOE]`, `[LEAD 2, SHEARS]`); `work-speed: 2.0` is twice a player |
| `jobs.<job>.area-radius`, `area-below`, `area-above`, `max-area-size`, `max-area-height` | | Hiring area and limits |
| `jobs.lumberjack.auto-plant`, `spacing`, `row-spacing`, `reserve` | true, 3, 5, 64 | |
| `jobs.miner.targets`, `x-ray`, `keep-tunnel-blocks` | ores, false, false | |
| `jobs.quarry.filler` | stone types | Plug and stair blocks |
| `jobs.builder.max-volume`, `preview-blocks`, `preview-seconds` | 2,000,000, 30,000, 60 | |
| `jobs.smelter.smelts`, `fuels` | raw ores and food, coal... | |
| `jobs.rancher.herd-size`, `animals` | 10, farm animals | |
| `jobs.guard.ignore` | endermen, piglins... | |
| `market.sell-ratio`, `demand`, `demand-recovery-hours`, `max-income-per-day`, `sell-anywhere`, `merchants-only` | 0.25, 256, 24, 300, false, true | |
| `market.over-limit-rate` | 0.25 | Players past the limit get this share |
| `jobs.builder.repair-check-seconds` | 60 | How often repair mode looks a build over |
| `jobs.courier.walk-distance` | 64 | Farther chests are reached by a shortcut |
| `jobs.guard.take-damage`, `knockout-seconds` | true, 60 | Mobs can hurt guards and bodyguards; down this long when knocked out |
| `jobs.rancher.keep-breeding` | true | A full herd keeps breeding and the oldest are culled |
| `report.on-join`, `min-away-minutes` | true, 30 | The "while you were away" report |
| `coreprotect.enabled`, `user-prefix` | true, `#` | Log employees' changes to CoreProtect |
| `market.real-world.enabled`, `amplify`, `min-multiplier`, `max-multiplier`, `tickers` | false, 3, 0.5, 2.0 | |
| `virtual-chests.delivery-interval-seconds`, `overflow-chests`, `group-size`, `groups` | 30, 10, 50, 100-600 | Moving a group (or Sort into groups) repoints storage, overflow, supplies, courier routes and /sort's virtual chests |
| `blueprints.downloads-enabled`, `max-download-mb` | true, 50 | The size limit covers uploads too |
| `blueprints.upload-url` | `""` | Empty: the plugin's own upload page (needs `web.enabled`); a URL: an outside page reading `plugins/ArcEmployees/uploads` |

Data files next to it: `employees.yml` (employees, written atomically), `boards.yml`, `links.yml`,
`market.yml` (merchants and sales history), `chests.yml` (moved chest groups), `avatars.yml`,
`zones.yml` (zone presets), `activity.yml` (when a player was last online),
`ledger.yml` (each employee's books for 30 days, and the away tallies), `managers.yml` (who may
manage whose employees), `villager-hiring.yml` (villages waiting for a replacement baby), `blueprints/`.

**ArcEstate:** with it installed, employees only work blocks outside every plot, or on land their
owner owns or is trusted on (guards still fight mobs anywhere).

---

## Building and deploying

```
mvn package          # target/ArcEmployees-1.0.0.jar
```

Needs Java 25. The pom uses `source/target 25` rather than `release 25`, so it also builds on a
Java 25 runtime without the full JDK's `lib/ct.sym`. Copy the jar into your server's `plugins/`
folder and restart the server (don't use `/reload`). Config-only changes need just `/emp reload`.

---

## How it works inside

- `job/Worker`: each employee's brain, ticked every server tick while its chunk is loaded. It picks a
  task, moves the Mannequin along its own paths and keeps it on the ground.
- Outdoor workers (guards, bodyguards, lumberjacks, farmers, ranchers) keep their home spot under
  open sky: a home under a roof is moved out onto open ground nearby, and an employee that gets
  boxed in right after going home is given open ground instead. Only lumberjacks walk through leaves.
- Employees open wooden doors and fence gates on their way through and close them behind them (iron
  doors need redstone). An employee that's too far from home (another world, 160+ blocks) or can't
  find the way back goes straight there; one whose floor is dug away drops to the next floor.
- Movement looks like a player's: heads and bodies turn over a few ticks instead of snapping, walking
  picks up speed over the first steps and slows into sharp corners, everyone walks their own line at
  their own pace, and idle employees look round, often at a player standing nearby.
- `util/Pathfinder`: A* for a player-sized walker. Modes: walk; dig (miners tunnel, pillar and
  bridge, also across lava and water by building into it; blocks to break or place ride along on
  each waypoint; long trips are planned in stretches; a block it fails to reach three times is left);
  fly (builders, like scaffolding). It never loads chunks and avoids liquids, fire and portals.
- `job/Job`: a job only decides the next task; delivering, picking up drops, idling and walking are
  shared. Jobs: `LumberjackJob`, `FarmerJob`, `MinerJob`, `QuarryJob`, `BuilderJob`, `SmelterJob`,
  `RancherJob`, `GuardJob`, `BodyguardJob`, `TraderJob`, `CourierJob`. To add one: a `JobType` constant, a `Job` implementation, a case in
  `Job.create`, a `jobs.<key>` section in config.yml and its defaults in `Settings`.
- `storage/VirtualChestsBridge`: VirtualChests 1.2.0 has no API, so it's reached by reflection.
  Deposits and withdrawals go to the live inventory if the chest is open, otherwise to its stored
  copy. `Supply` (materials), `ChestGroups` (numbering, moves).
- `build/`: blueprint readers (`Nbt`, `BlueprintLoader`, `WorldEditFormats`), the library and
  downloads (`Blueprints`), placement and rotation (`BuildOrder`), ghost previews (`BuildPreview`).
- `market/`: merchants, prices, demand and limits (`Market`), real-world prices (`RealPrices`), the
  sell window and price list.
- `board/`: status and sales boards (`ControlBoards`), avatars (`Avatars`), the problem/event log
  (`EventLog`), buttons and levers (`Links`).
- `gui/`: every menu; `Menu` is the base (clicks cancelled, redrawn each second).
- `EmployeeManager`: entities, chunk tickets, hiring, save/load (`EmployeeStore`). `Food` (eating and
  pantries), `Schedule` (the working day), `Activity` (the status line), `HireCost` (what a hire asks for).
- `VillagerHiring`: shift-right-click on villagers, who may be hired, turning a villager into an
  employee (and back when fired), and the replacement babies; `gui/HireMenu` is its job picker.
- `Ledger`: hourly books per employee (goods stored, sales, materials, activity), and the
  away tallies; `Reports` turns them into the join report, `/emp report` and `/emp profit`.
- `util/BlockLog`: CoreProtect logging, by reflection (nothing happens without CoreProtect).
