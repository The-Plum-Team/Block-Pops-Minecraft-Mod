# Runs once on world load (minecraft:load tag). Starts screenshots in clear daylight.
weather clear
time set day
gamerule doDaylightCycle false
fill -3 -60 -3 3 -60 2 minecraft:stone
setblock 0 -60 3 blockpops:claw_machine_block[facing=south,half=lower]{CollectionId:"onepiece"}
setblock 0 -59 3 blockpops:claw_machine_block[facing=south,half=upper]
# One wall of real box blocks per collection, for the in-world scenario.
function blockpops_e2e:showcase
