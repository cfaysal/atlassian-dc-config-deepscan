import org.codehaus.groovy.control.CompilationUnit;
import org.codehaus.groovy.control.CompilerConfiguration;
import org.codehaus.groovy.control.Phases;
import org.codehaus.groovy.control.customizers.ASTTransformationCustomizer;
import groovy.lang.GroovyClassLoader;
import groovy.transform.TypeChecked;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

String target = System.getProperty("target");
String[] dirs = {
  "/opt/atlassian/confluence/confluence/WEB-INF/lib",
  "/opt/atlassian/confluence/confluence/WEB-INF/atlassian-bundled-plugins",
  "/opt/atlassian/confluence/lib"
};
List<String> cp = new ArrayList<String>();
cp.add("/tmp/groovy.jar");
cp.add("/tmp/groovy-json.jar");
  for (java.io.File pc : new java.io.File("/var/atlassian/application-data/confluence/plugins-cache").listFiles()) { if (pc.getName().endsWith(".jar")) cp.add(pc.getAbsolutePath()); }
for (String dir : dirs) {
  File[] fs = new File(dir).listFiles();
  if (fs != null) { for (File f : fs) { if (f.getName().endsWith(".jar")) cp.add(f.getAbsolutePath()); } }
}
System.out.println("classpath jars: " + cp.size());
System.out.println("target: " + target);

CompilerConfiguration cc = new CompilerConfiguration();
cc.setClasspathList(cp);
cc.addCompilationCustomizers(new ASTTransformationCustomizer(TypeChecked.class));
GroovyClassLoader gcl = new GroovyClassLoader(Thread.currentThread().getContextClassLoader(), cc);
CompilationUnit cu = new CompilationUnit(cc, null, gcl);
cu.addSource(new File(target));
System.out.println("phase: INSTRUCTION_SELECTION (" + Phases.INSTRUCTION_SELECTION + "), where @TypeChecked actually runs");
try {
  cu.compile(Phases.INSTRUCTION_SELECTION);
  System.out.println("TYPECHECK CLEAN");
} catch (Exception e) {
  System.out.println("TYPE ERRORS BEGIN");
  System.out.println(e.getMessage());
  System.out.println("TYPE ERRORS END");
}
/exit
