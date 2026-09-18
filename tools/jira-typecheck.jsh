import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import groovy.lang.GroovyClassLoader;
import groovy.transform.TypeChecked;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

void runTypecheck() throws Exception {
  String target = System.getProperty("target");
  String jiraInstall = System.getProperty("jiraInstall");
  String jiraSharedHome = System.getProperty("jiraSharedHome");
  String groovyJar = System.getProperty("groovyJar");
  String groovyJsonJar = System.getProperty("groovyJsonJar");
  if (target == null || jiraInstall == null || jiraSharedHome == null || groovyJar == null || groovyJsonJar == null) {
    throw new IllegalArgumentException("Required properties: target, jiraInstall, jiraSharedHome, groovyJar, groovyJsonJar");
  }

  List<String> cp = new ArrayList<String>();
  cp.add(groovyJar);
  cp.add(groovyJsonJar);
  String[] dirs = {
    jiraInstall + "/atlassian-jira/WEB-INF/lib",
    jiraInstall + "/atlassian-jira/WEB-INF/atlassian-bundled-plugins",
    jiraInstall + "/lib",
    jiraSharedHome + "/plugins/installed-plugins",
    jiraSharedHome + "/plugins/.osgi-plugins/transformed-plugins"
  };
  for (String dir : dirs) {
    File[] files = new File(dir).listFiles();
    if (files != null) {
      for (File file : files) {
        if (file.getName().endsWith(".jar")) cp.add(file.getAbsolutePath());
      }
    }
  }
  if (!new File(target).isFile()) throw new IllegalArgumentException("Target is not a file: " + target);
  System.out.println("classpath jars: " + cp.size());
  System.out.println("target: " + target);

  CompilerConfiguration cc = new CompilerConfiguration();
  cc.setClasspathList(cp);
  cc.addCompilationCustomizers(new ASTTransformationCustomizer(TypeChecked.class));
  GroovyClassLoader gcl = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), cc);
  CompilationUnit cu = new CompilationUnit(cc, null, gcl);
  cu.addSource(new File(target));
  System.out.println("phase: INSTRUCTION_SELECTION (" + Phases.INSTRUCTION_SELECTION + ")");
  cu.compile(Phases.INSTRUCTION_SELECTION);
  System.out.println("TYPECHECK CLEAN");
}

int typecheckExitCode = 0;
try {
  runTypecheck();
} catch (Throwable error) {
  System.out.println("TYPECHECK FAILED: " + error.getClass().getName());
  error.printStackTrace(System.out);
  typecheckExitCode = 1;
}
/exit typecheckExitCode
