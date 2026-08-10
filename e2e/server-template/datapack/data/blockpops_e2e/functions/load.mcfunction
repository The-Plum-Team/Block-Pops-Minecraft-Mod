# Runs once on world load (minecraft:load tag). Starts screenshots in clear daylight.
weather clear
time set day
gamerule doDaylightCycle false
setblock 0 -60 3 blockpops:claw_machine_block[facing=south,half=lower]{CollectionId:"onepiece"}
setblock 0 -59 3 blockpops:claw_machine_block[facing=south,half=upper]
