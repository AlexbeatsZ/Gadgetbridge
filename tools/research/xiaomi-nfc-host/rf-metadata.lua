-- Read-only RF callback metadata; no route change, fd read or APDU.
local base="/data/quickapp/files/com.meta.s4nativeprobe"
local ready=io.open(base.."/ready.txt","r");if not ready then return end;ready:close()
local done=io.open(base.."/rf-meta-done.txt","r");if done then done:close();return end
local commands={
 {"version.txt","uname -a"},
 {"host-help.txt","xd 2c8b459c 112"},
 {"fd-help.txt","xd 2c8b46a8 104"},
 {"module-load.txt","xd 3c2d4720 4"},
 {"modules-before.txt","xd 3c2d4b90 4"},
 {"modules-after.txt","xd 3c2d4c30 4"},
 {"module-unload.txt","xd 3c2d4748 8"},
 {"modules-final.txt","xd 3c2d46fc 4"},
 {"nfc-proc.txt","xd 2c3420d0 48"},
 {"nfc-fds.txt","xd 2c34319e 48"},
 {"nfc-mqueue.txt","xd 2c34dff0 24"},
 {"proc-list.txt","xd 3c2d46d4 4"},
}
local result={"S4 3.201.79 RF metadata; readonly"}
for _,item in ipairs(commands) do
 local ok,a,b,c=pcall(function() return os.execute(item[2].." > "..base.."/"..item[1]) end)
 result[#result+1]=item[2].." => "..tostring(ok)..","..tostring(a)..","..tostring(b)..","..tostring(c)
end
local out=io.open(base.."/lua-result.txt","w");if out then out:write(table.concat(result,"\n"));out:close() end
local f=io.open(base.."/rf-meta-done.txt","w");if f then f:write("done");f:close() end
