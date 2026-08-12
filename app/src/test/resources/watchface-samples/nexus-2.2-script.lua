-- Nexus 2.2-style live wallpaper engine
-- Port of the AOSP nexus.rs RenderScript logic
-- (packages/wallpapers/Basic, android-2.2_r1.1).
-- 50 pre-made trail layers share a pool: NORMALS wander the grid
-- at a steady count; a tap claims 4 that fly out of the tapped cell.

local N        = 50
local NORMALS  = 12
local SPEED    = 200.0
local VMIN     = 0.8
local VSPAN    = 0.3
local XSPEED   = 300.0
local HALF     = 256.0
local CELL     = 14.0
local TRAIL    = 560.0
local RECYCLE  = 841.0
local OFF      = 264.0
local STRAGGLE = 0.50
local SPAWN_N  = -298.0
local SPAWN_P  = 290.0
local COLS     = { 'ff0000', '00cc00', '0066e6', 'ffcc00' }

var_NOW = var_NOW or 0
var_TP = var_TP or {}
for i = 0, N - 1 do
  var_TP[i] = var_TP[i] or {}
end

local function randcell()
  return -HALF + math.random(0, 36) * CELL
end

local function pinit(p, ttype)
  local r = math.random(1, 4)
  if r == 1 then
    p.ox = randcell(); p.oy = SPAWN_N
    p.vx = 0; p.vy = SPEED * (VMIN + math.random() * VSPAN)
  elseif r == 2 then
    p.ox = randcell(); p.oy = SPAWN_P
    p.vx = 0; p.vy = -SPEED * (VMIN + math.random() * VSPAN)
  elseif r == 3 then
    p.ox = SPAWN_N; p.oy = randcell()
    p.vx = SPEED * (VMIN + math.random() * VSPAN); p.vy = 0
  else
    p.ox = SPAWN_P; p.oy = randcell()
    p.vx = -SPEED * (VMIN + math.random() * VSPAN); p.vy = 0
  end
  p.type = ttype
  p.color = math.random(0, 3)
  p.active = 1
  p.born = var_NOW
  if ttype == 0 then
    p.start = var_NOW + math.random() * STRAGGLE   -- brief stagger only
  else
    p.start = var_NOW
  end
end

-- free slot first, else steal the oldest in-use pulse (teleport)
local function claim()
  for i = 0, N - 1 do
    if var_TP[i].active ~= 1 then return var_TP[i] end
  end
  local oldest, best = nil, 1e18
  for i = 0, N - 1 do
    local p = var_TP[i]
    if p.active == 1 and p.born < best then
      best = p.born
      oldest = p
    end
  end
  return oldest
end

function t_spawn_normal()
  local p = claim()
  if p then pinit(p, 0) end
end

-- 10x10 touch grid cell tap: 4 trails fly out of the cell
function nexus_tap(idx)
  local col = idx % 10
  local row = math.floor(idx / 10)
  local cx = -230.4 + col * 51.2
  local cy = -230.4 + row * 51.2
  local x = math.floor((cx + HALF) / CELL) * CELL - HALF
  local y = math.floor((cy + HALF) / CELL) * CELL - HALF
  local color = math.random(0, 3)
  local dirs = { { 1, 0 }, { -1, 0 }, { 0, 1 }, { 0, -1 } }
  for d = 1, 4 do
    local p = claim()
    if p then
      p.ox = x; p.oy = y
      p.vx = dirs[d][1] * XSPEED
      p.vy = dirs[d][2] * XSPEED
      p.type = 1
      p.color = color
      color = color + 1
      if color >= 4 then color = 0 end
      p.start = var_NOW
      p.born = var_NOW
      p.active = 1
    end
  end
end

function on_millisecond(dt)
  if dt > 0.5 then dt = 0.5 end
  var_NOW = var_NOW + dt
  for i = 0, N - 1 do
    local p = var_TP[i]
    if p.active == 1 and var_NOW >= p.start then
      local gx = p.ox + 7 + p.vx * (var_NOW - p.start)
      local gy = p.oy + 7 + p.vy * (var_NOW - p.start)
      if (p.vx > 0 and gx >= RECYCLE) or (p.vx < 0 and gx <= -RECYCLE)
         or (p.vy > 0 and gy >= RECYCLE) or (p.vy < 0 and gy <= -RECYCLE) then
        p.active = 0
      end
    end
  end
  local cnt = 0
  for i = 0, N - 1 do
    local p = var_TP[i]
    if p.active == 1 and p.type == 0 then cnt = cnt + 1 end
  end
  while cnt < NORMALS do
    t_spawn_normal()
    cnt = cnt + 1
  end
end

-- per-layer getters (evaluated by the renderer every frame)
-- Names are var_-prefixed because the XML references them as bare
-- identifiers: CloX indexes every script global (functions included)
-- through its var proxy, so var_T_x resolves to this function.
function var_T_rot(i)
  local p = var_TP[i]
  if p.active ~= 1 or var_NOW < p.start then return 0 end
  if p.vx > 0 then return 0 elseif p.vx < 0 then return 180
  elseif p.vy > 0 then return 90 else return 270 end
end

function var_T_x(i)
  local p = var_TP[i]
  if p.active ~= 1 or var_NOW < p.start then return -9999 end
  local gx = p.ox + 7 + p.vx * (var_NOW - p.start)
  local a = math.rad(var_T_rot(i))
  return gx - OFF * math.cos(a)
end

function var_T_y(i)
  local p = var_TP[i]
  if p.active ~= 1 or var_NOW < p.start then return -9999 end
  local gy = p.oy + 7 + p.vy * (var_NOW - p.start)
  local a = math.rad(var_T_rot(i))
  return gy - OFF * math.sin(a)
end

function var_T_col(i)
  local p = var_TP[i]
  if p.active ~= 1 then return 'ffffff' end
  -- COLS is a Lua table: 1-based. p.color is 0..3 -> index 1..4.
  return COLS[(p.color or 0) + 1]
end

function var_T_op(i)
  local p = var_TP[i]
  if p.active ~= 1 or var_NOW < p.start then return 0 end
  return 100
end
