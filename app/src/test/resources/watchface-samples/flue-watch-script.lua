var_frame = var_frame or 1
var_ms = var_ms or 0
var_first = var_first or 1

function restart_play()
    var_frame = 1
    var_ms = 0
    var_first = 1
    on_millisecond = function(dt)
        if var_first == 1 then
            var_first = 0
            return
        end
        if dt > 0.1 then dt = 0.1 end
        var_ms = var_ms + math.floor(dt * 1000 + 0.5)
        local nf = 1 + math.floor(var_ms / 50)
        if nf >= 67 then
            var_frame = 67
            var_ms = 0
            on_millisecond = nil
        else
            var_frame = nf
        end
    end
end
