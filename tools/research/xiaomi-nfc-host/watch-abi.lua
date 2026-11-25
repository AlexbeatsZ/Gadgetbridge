-- Read-only metadata for S4 3.201.79. No NFC fd open/read/write/ioctl.
-- Reuse com.meta.s4nativeprobe and face ID 362991121; do not create new IDs.
local base="/data/quickapp/files/com.meta.s4nativeprobe"
local ready=io.open(base.."/ready.txt","r")
if not ready then return end
ready:close()
local done=io.open(base.."/abi-read-done.txt","r")
if done then done:close();return end
local commands={
 {"host-help.txt","xd 2c8b4578 24"}, -- original CE action table
 {"fd-help.txt","xd 2c340eac 8"},   -- return-true stub, no controller calls
 {"nfc-proc.txt","fdinfo 47"},
 {"nfc-fds.txt","xd 3c203dac 4"},   -- registered CE subsystem table
 {"nfc-mqueue.txt","ls /var/mqueue"},
 {"process-list.txt","ps"},
 {"modules-final.txt","lsmod"},
 {"modules-before.txt","xd 3c2d46d4 4"}, -- eSE flags (not applet/card content)
 {"modules-after.txt","xd 3c2d46f8 4"},  -- optional vendor-handler pointer
 {"module-load.txt","xd 2c9d235c 40"},  -- ROM file_operations table
 {"module-unload.txt","xd 3c2d3708 4"}  -- TML context pointer only
}
local result={"S4 3.201.79 NFC ABI metadata; read-only"}
for _,item in ipairs(commands) do
 local ok,a,b,c=pcall(function() return os.execute(item[2].." > "..base.."/"..item[1]) end)
 result[#result+1]=item[2].." => "..tostring(ok)..","..tostring(a)..","..tostring(b)..","..tostring(c)
end
local out=io.open(base.."/lua-result.txt","w")
if out then out:write(table.concat(result,"\n"));out:close() end
local d=io.open(base.."/abi-read-done.txt","w")
if d then d:write("done");d:close() end
