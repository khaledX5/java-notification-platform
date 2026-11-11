-- Token bucket for one recipient on one channel.
--
-- The bucket holds up to `capacity` tokens and earns one back every `refill_ms` milliseconds.
-- Sending a message takes one token; an empty bucket means the recipient has to wait.
--
-- Refilling and taking a token happen inside this one script. Redis runs a script as a single
-- step, so two workers can never both take the last token.
--
-- KEYS[1]  the bucket
-- ARGV[1]  capacity: most tokens the bucket holds, i.e. the burst a recipient can get at once
-- ARGV[2]  refill_ms: milliseconds it takes to earn one token back
--
-- Returns 1 if a token was taken, 0 if the bucket was empty.

local capacity = tonumber(ARGV[1])
local refill_ms = tonumber(ARGV[2])

-- The clock of Redis, not of the caller: workers whose clocks drift still agree on the time.
local time = redis.call('TIME')
local now_ms = time[1] * 1000 + math.floor(time[2] / 1000)

-- A bucket that does not exist yet starts full.
local bucket = redis.call('HMGET', KEYS[1], 'tokens', 'refilled_at')
local tokens = tonumber(bucket[1]) or capacity
local refilled_at = tonumber(bucket[2]) or now_ms

tokens = math.min(capacity, tokens + (now_ms - refilled_at) / refill_ms)

local allowed = 0
if tokens >= 1 then
  tokens = tokens - 1
  allowed = 1
end

redis.call('HSET', KEYS[1], 'tokens', tokens, 'refilled_at', now_ms)
-- A bucket nobody touches is full again after capacity * refill_ms, so it can be dropped by then.
redis.call('PEXPIRE', KEYS[1], capacity * refill_ms)

return allowed
