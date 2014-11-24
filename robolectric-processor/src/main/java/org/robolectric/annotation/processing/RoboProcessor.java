package org.robolectric.annotation.processing;

import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedOptions;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.SourceVersion;
import javax.tools.Diagnostic.Kind;
import javax.tools.FileObject;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;

/**
 * Annotation processor entry point for Robolectric annotations.
 */
@SupportedOptions(RoboProcessor.PACKAGE_OPT)
@SupportedAnnotationTypes("org.robolectric.annotation.*")
@SupportedSourceVersion(SourceVersion.RELEASE_6)
public class RoboProcessor extends AbstractProcessor {

  static final String PACKAGE_OPT = "org.robolectric.annotation.processing.shadowPackage";

  RoboModel model;
  private Messager messager;
  private Map<TypeElement,Validator> elementValidators =
      new HashMap<TypeElement,Validator>(13);

  private void addValidator(Validator v) {
    elementValidators.put(v.annotationType, v);
  }

  /**
   * Default constructor - necessary for the tooling environment.
   */
  public RoboProcessor() {}

  /**
   * Constructor to use for testing passing options in. Only
   * necessary until compile-testing supports passing options
   * in.
   *
   * @param options simulated options that would ordinarily
   * be passed in the {@link ProcessingEnvironment}.
   */
  RoboProcessor(Map<String,String> options) {
	processOptions(options);
  }

  private Map<String,String> options;
  private String shadowPackage;

  private void processOptions(Map<String,String> options) {
    if (this.options == null) {
      this.options = options;
      shadowPackage = options.get(PACKAGE_OPT);
    }
  }

  @Override
  public void init(ProcessingEnvironment env) {
    super.init(env);
    processOptions(env.getOptions());
    model = new RoboModel(env.getElementUtils(),
                          env.getTypeUtils());
    messager = processingEnv.getMessager();
    messager.printMessage(Kind.NOTE, "Initialising RAP");
    addValidator(new ImplementationValidator(model, env));
    addValidator(new ImplementsValidator(model, env));
    addValidator(new RealObjectValidator(model, env));
    addValidator(new ResetterValidator(model, env));
  }

  private boolean generated = false;

  @Override
  public boolean process(Set<? extends TypeElement> annotations,
      RoundEnvironment roundEnv) {
    for (TypeElement annotation : annotations) {
      Validator validator = elementValidators.get(annotation);
      if (validator != null) {
        for (Element elem : roundEnv.getElementsAnnotatedWith(annotation)) {
          validator.visit(elem, elem.getEnclosingElement());
        }
      }
    }

    if (!generated && shadowPackage != null) {
      model.prepare();
      render();
      generated = true;
    }
    return true;
  }

  private static final String GEN_CLASS = "Shadows";

  private void render() {
    // TODO: Because this was fairly simple to begin with I haven't
    // included a templating engine like Velocity but simply used
    // raw print() statements, in an effort to reduce the number of
    // dependencies that RAP has. However, if it gets too complicated
    // then using Velocity might be a good idea.

    String genFQ = shadowPackage + '.' + GEN_CLASS;

    messager.printMessage(Kind.NOTE, "Generating output file " + genFQ);
    final Filer filer = processingEnv.getFiler();
    try {
      JavaFileObject jfo = filer.createSourceFile(genFQ);
      PrintWriter writer = new PrintWriter(jfo.openWriter());
      try {
      writer.print("package " + shadowPackage + ";\n");
      for (String name: model.imports) {
        writer.println("import " + name + ';');
      }
      writer.println();
      writer.println("/**");
      writer.println(" * Shadow mapper. Automatically generated by the Robolectric Annotation Processor.");
      writer.println(" */");
      writer.println("@Generated(\"" + RoboProcessor.class.getCanonicalName() + "\")");
      writer.println("public class " + GEN_CLASS + " implements ShadowProvider {");
      writer.println();
      writer.print  ("  public static final Class<?>[] DEFAULT_SHADOW_CLASSES = {");
      boolean firstIteration = true;
      for (TypeElement shadow : model.shadowTypes.keySet()) {
        if (firstIteration) {
          firstIteration = false;
        } else {
          writer.print(",");
        }
        writer.print("\n    " + model.getReferentFor(shadow) + ".class");
      }
      writer.println("\n  };\n");
      for (Entry<TypeElement,TypeElement> entry: model.getShadowMap().entrySet()) {
        final TypeElement actualType = entry.getValue();
        if (!actualType.getModifiers().contains(Modifier.PUBLIC)) {
          continue;
        }
        // Generics not handled specifically as yet.
//        int paramCount = 0;
//        StringBuilder builder = new StringBuilder("<");
//        for (TypeParameterElement typeParam : entry.getValue().getTypeParameters()) {
//          if (paramCount > 0) {
//            builder.append(',');
//          }
//          builder.append(typeParam).append(" extends ");
//          for (TypeMirror bound : typeParam.getBounds()) {
//            builder.append(bound).append(" & ");
//          }
//          paramCount++;
//          processingEnv.getElementUtils().printElements(writer, typeParam);
//        }
//        final String typeString = paramCount > 0 ? builder.append("> ").toString() : "";

        final String actual = model.getReferentFor(actualType);
        final String shadow = model.getReferentFor(entry.getKey());
        writer.println("  public static " + shadow + " shadowOf(" + actual + " actual) {");
        writer.println("    return (" + shadow + ") shadowOf_(actual);");
        writer.println("  }");
        writer.println();
      }
      writer.println("  public void reset() {");
      for (Entry<TypeElement,ExecutableElement> entry: model.resetterMap.entrySet()) {
        writer.println("    " + model.getReferentFor(entry.getKey()) + "." + entry.getValue().getSimpleName() + "();");
      }
      writer.println("  }\n");

      writer.println("  @SuppressWarnings({\"unchecked\"})");
      writer.println("  public static <P, R> P shadowOf_(R instance) {");
      writer.println("    return (P) ShadowExtractor.extract(instance);");
      writer.println("  }");

      writer.println('}');
      } finally {
        writer.close();
      }

      generateServiceLoaderMetadata(genFQ, filer);
    } catch (IOException e) {
      // TODO: Better error handling?
      throw new RuntimeException(e);
    }
  }

  private void generateServiceLoaderMetadata(String shadowClassName, Filer filer) {
    try {
      String fileName = "org.robolectric.util.ShadowProvider";
      processingEnv.getMessager().printMessage(Kind.NOTE, "Writing META-INF/services/" + fileName);
      FileObject file = filer.createResource(StandardLocation.CLASS_OUTPUT, "", "META-INF/services/" + fileName);
      PrintWriter pw = new PrintWriter(new OutputStreamWriter(file.openOutputStream(), "UTF-8"));
      pw.println(shadowClassName);
      pw.close();
    } catch (IOException e) {
      processingEnv.getMessager().printMessage(Kind.ERROR, " Failed to write service loader metadata file: " + e);
    }
  }
}
