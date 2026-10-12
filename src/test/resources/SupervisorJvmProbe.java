import java.util.ArrayList;
import java.util.List;

public class SupervisorJvmProbe {

    public static void main(String[] args) throws Exception {
        List<String> command = new ArrayList<>(
                List.of(
                        "python3",
                        args[0],
                        "--parent-pid",
                        Long.toString(ProcessHandle.current().pid()),
                        "--ownership",
                        args[1],
                        "--cwd",
                        args[2],
                        "--deadline-seconds",
                        "30",
                        "--",
                        "node",
                        "-e",
                        "require('child_process').spawn('/bin/sleep',['120'],{stdio:'ignore'});setInterval(()=>{},1000)"
                )
        );
        Process supervisor = new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        supervisor.waitFor();
    }
}
