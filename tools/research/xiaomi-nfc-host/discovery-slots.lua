-- Read only the two original RF discovery registrations and ownership metadata.
-- No RF buffers, device fd, SE session, registration, routing or callback write.
local base="/data/quickapp/files/com.meta.s4nativeprobe"
local ready=io.open(base.."/ready.txt","r");if not ready then return end;ready:close()
local done=io.open(base.."/discovery-slots-done.txt","r");if done then done:close();return end
local commands={
 {"version.txt","uname -a"},{"host-help.txt","xd 3c2d4754 40"},
 {"fd-help.txt","xd 3c2d481a 6"},{"nfc-proc.txt","xd 3c2d4746 16"},
 {"nfc-fds.txt","xd 3c2d4b90 4"},{"nfc-mqueue.txt","xd 3c2d4c30 4"},
 {"shell-help.txt","xd 2c344754 64"},{"process-list.txt","xd 2c341310 24"},
 {"device-list.txt","xd 3c2d45cc 4"},{"proc-list.txt","xd 3c2d46d4 4"},
 {"modules-final.txt","lsmod"},
}
local result={"S4 original discovery slots; readonly"}
for _,item in ipairs(commands)do
 local ok,a,b,c=pcall(function()return os.execute(item[2].." > "..base.."/"..item[1])end)
 result[#result+1]=item[2]..":"..tostring(ok)..","..tostring(a)..","..tostring(b)..","..tostring(c)
end
local f=io.open(base.."/lua-result.txt","w");if f then f:write(table.concat(result,"\n"));f:close()end
f=io.open(base.."/discovery-slots-done.txt","w");if f then f:write("done");f:close()end
