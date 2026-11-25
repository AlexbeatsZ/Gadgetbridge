-- S4 3.201.79 only: selected controller capability/state metadata, read-only.
-- No device fd, reset/init, module, routing change, SE session or RF payload.
local base="/data/quickapp/files/com.meta.s4nativeprobe"
local ready=io.open(base.."/ready.txt","r");if not ready then return end;ready:close()
local done=io.open(base.."/controller-meta-done.txt","r");if done then done:close();return end
local commands={
 {"version.txt","uname -a"},
 {"host-help.txt","xd 3c2d4c4c 8"}, -- NFC+c0: features,u16 capacity,u8 connections,u8 ctrl payload
 {"fd-help.txt","xd 3c2d4d84 4"}, -- NFC+1f8: NCI version/manufacturer metadata
 {"nfc-proc.txt","xd 3c2d4cbc 4"}, -- NFC+130: controller state/reassembly flags
 {"nfc-fds.txt","xd 3c2d4746 4"}, -- DM+4a: discovery flags/state/next-state
 {"nfc-mqueue.txt","xd 3c2d4c54 16"}, -- NFC+c8: discovery-map pointer/interfaces/count
 {"device-list.txt","xd 3c2d45cc 4"}, -- current HAL callback owner
 {"proc-list.txt","xd 3c2d46d4 4"}, -- existing eSE flags
 {"modules-final.txt","lsmod"},
 {"shell-help.txt","xd 2c34d018 24"}, -- original CORE_INIT capability stores
}
local result={"S4 controller metadata; readonly; no routing or SE writes"}
for _,item in ipairs(commands) do
 local ok,a,b,c=pcall(function()return os.execute(item[2].." > "..base.."/"..item[1])end)
 result[#result+1]=item[2].." => "..tostring(ok)..","..tostring(a)..","..tostring(b)..","..tostring(c)
end
local out=io.open(base.."/lua-result.txt","w");if out then out:write(table.concat(result,"\n"));out:close() end
local f=io.open(base.."/controller-meta-done.txt","w");if f then f:write("done");f:close() end
