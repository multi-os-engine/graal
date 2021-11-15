package com.oracle.svm.core.graal.llvm.debug;

import com.oracle.graal.pointsto.infrastructure.WrappedJavaMethod;
import com.oracle.svm.core.SubstrateOptions;
import com.oracle.svm.core.SubstrateUtil;
import com.oracle.svm.core.graal.llvm.LLVMGenerator;
import com.oracle.svm.core.graal.llvm.util.LLVMIRBuilder;
import com.oracle.svm.core.graal.llvm.util.LLVMOptions;
import com.oracle.svm.core.graal.llvm.util.LLVMUtils;
import com.oracle.svm.hosted.annotation.CustomSubstitutionMethod;
import com.oracle.svm.hosted.annotation.CustomSubstitutionType;
import com.oracle.svm.hosted.image.sources.SourceManager;
import com.oracle.svm.hosted.lambda.LambdaSubstitutionType;
import com.oracle.svm.hosted.meta.HostedField;
import com.oracle.svm.hosted.meta.HostedMethod;
import com.oracle.svm.hosted.meta.HostedType;
import com.oracle.svm.hosted.substitute.InjectedFieldsType;
import com.oracle.svm.hosted.substitute.SubstitutionMethod;
import com.oracle.svm.hosted.substitute.SubstitutionType;
import com.oracle.svm.shadowed.org.bytedeco.javacpp.PointerPointer;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMAttributeRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMBuilderRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMContextRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMDIBuilderRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMMetadataRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMModuleRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMTypeRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMValueMetadataEntry;
import com.oracle.svm.shadowed.org.bytedeco.llvm.LLVM.LLVMValueRef;
import com.oracle.svm.shadowed.org.bytedeco.llvm.global.LLVM;
import jdk.vm.ci.meta.ResolvedJavaMethod;
import jdk.vm.ci.meta.ResolvedJavaType;
import org.graalvm.compiler.debug.DebugContext;
import org.graalvm.compiler.graph.NodeSourcePosition;
import org.graalvm.compiler.nodes.ValueNode;
import org.graalvm.nativeimage.ImageSingletons;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;

public class LLVMDebugInfoBuilder {

    private LLVMContextRef context;
    private LLVMDIBuilderRef diBuilder;
    private LLVMBuilderRef builder;

    private LLVMMetadataRef cu;
    private LLVMMetadataRef file;
    private LLVMMetadataRef subprogram;
    //private LLVMMetadataRef scope;//TODO Cu should need?
    private ResolvedJavaMethod method;
    private Path path;

    public LLVMDebugInfoBuilder(DebugContext debugContext, ResolvedJavaMethod method, LLVMContextRef context, LLVMModuleRef moduleRef, LLVMBuilderRef builderRef){
        this.context = context;
        this.method = method;
        this.builder = builderRef;

        ResolvedJavaType javaType = getDeclaringClass((HostedMethod) method, false);
        HostedType hostedType = ((HostedMethod) method).getDeclaringClass();
        Class<?> clazz = hostedType.getJavaClass();
        SourceManager sourceManager = ImageSingletons.lookup(SourceManager.class);
        path = sourceManager.findAndCacheSource(javaType, clazz, debugContext);

        diBuilder = LLVM.LLVMCreateDIBuilder(moduleRef);

         if (path == null) {
             //TODO could also happen when not defining -H:DebugInfoSourceSearchPath. Should be handled better
             //TODO This seems to be the case for Proxys
             // TODO At first just emit dummy files
             file = LLVM.LLVMDIBuilderCreateFile(diBuilder, (String) null, 0, null, 0);
             //return;
         } else {
             path = Paths.get(SubstrateOptions.DebugInfoSourceCacheRoot.getValue(), path.toString());
             file = LLVM.LLVMDIBuilderCreateFile(diBuilder, path.getFileName().toString(), path.getFileName().toString().length(), path.getParent().toAbsolutePath().toString(), path.getParent().toAbsolutePath().toString().length());
         }

        cu = LLVM.LLVMDIBuilderCreateCompileUnit(diBuilder,
                LLVM.LLVMDWARFSourceLanguageJava,
                file,
                "LLVM version 12.0.1-3-g6e0a5672bc-bgf11ed69a5a",
                "LLVM version 12.0.1-3-g6e0a5672bc-bgf11ed69a5a".length(),
                0,
                null,
                0,
                3,
                null,
                0,
                LLVM.LLVMDWARFEmissionFull,
                0,
                0,
                0,
                null,
                0,
                null,
                0);
        LLVM.LLVMAddModuleFlag(moduleRef, LLVM.LLVMModuleFlagBehaviorOverride, "Dwarf Version", "Dwarf Version".length(), LLVM.LLVMValueAsMetadata(LLVM.LLVMConstInt(LLVM.LLVMInt32TypeInContext(context), 4, 0)));
        LLVM.LLVMAddModuleFlag(moduleRef, LLVM.LLVMModuleFlagBehaviorOverride, "Debug Info Version", "Debug Info Version".length(), LLVM.LLVMValueAsMetadata(LLVM.LLVMConstInt(LLVM.LLVMInt32TypeInContext(context), 3, 0)));
        LLVMValueRef functionRef = LLVM.LLVMGetNamedFunction(moduleRef, SubstrateUtil.uniqueShortName(method));

        // TODO: 15.11.2021 We need to also emit debug info for proxies, when no source code is available
        int line = 0;
        if (method.getLineNumberTable() != null) {
            //TODO What das this case mean?
            line = method.getLineNumberTable().getLineNumbers()[0];
            //return;
        }

        /*LLVM.LLVMDIBuilderCreateParameterVariable(diBuilder,
                cu,//TODO Correct scope?
                );*/
        //TODO Parametertypes needs to be defined
        LLVMMetadataRef subRoutineType = LLVM.LLVMDIBuilderCreateSubroutineType(diBuilder, file, (LLVMMetadataRef) null, 0, 0);
        subprogram = LLVM.LLVMDIBuilderCreateFunction(diBuilder,
                cu,
                name(),// Maybe better a combination of Class name and method name better
                name().length(),
                SubstrateUtil.uniqueShortName(method),
                SubstrateUtil.uniqueShortName(method).length(),
                file,
                line,
                subRoutineType,
                1,
                1,
                0,
                0,
                0);
        LLVM.LLVMSetSubprogram(functionRef, subprogram);
    }

    public void emitLocation(int line) {
        if (subprogram == null) {
            //Currently wont happen
            //TODO When the lineNumberTable was empty
            return;
        }
        LLVM.LLVMSetCurrentDebugLocation2(builder, LLVM.LLVMDIBuilderCreateDebugLocation(context, line, 0, subprogram, null));
    }

    public void finalizeBuilder() {
        LLVM.LLVMDIBuilderFinalize(diBuilder);
        LLVM.LLVMDisposeDIBuilder(diBuilder);
    }

    /*
    * TODO Copied from NativeImageDebugInfoProvider
    */
    public String name() {
        ResolvedJavaMethod targetMethod = method;
        while (targetMethod instanceof WrappedJavaMethod) {
            targetMethod = ((WrappedJavaMethod) targetMethod).getWrapped();
        }
        if (targetMethod instanceof SubstitutionMethod) {
            targetMethod = ((SubstitutionMethod) targetMethod).getOriginal();
        } else if (targetMethod instanceof CustomSubstitutionMethod) {
            targetMethod = ((CustomSubstitutionMethod) targetMethod).getOriginal();
        }
        String name = targetMethod.getName();
        if (name.equals("<init>")) {
            if (method instanceof HostedMethod) {
                name = getDeclaringClass((HostedMethod) method, true).toJavaName();
                if (name.indexOf('.') >= 0) {
                    name = name.substring(name.lastIndexOf('.') + 1);
                }
                if (name.indexOf('$') >= 0) {
                    name = name.substring(name.lastIndexOf('$') + 1);
                }
            } else {
                name = targetMethod.format("%h");
                if (name.indexOf('$') >= 0) {
                    name = name.substring(name.lastIndexOf('$') + 1);
                }
            }
        }
        return name;
    }

    protected static ResolvedJavaType getDeclaringClass(HostedMethod hostedMethod, boolean wantOriginal) {
        if (wantOriginal) {
            return getOriginal(hostedMethod.getDeclaringClass());
        }
        // we want a substituted target if there is one. if there is a substitution at the end of
        // the method chain fetch the annotated target class
        ResolvedJavaMethod javaMethod = hostedMethod.getWrapped().getWrapped();
        if (javaMethod instanceof SubstitutionMethod) {
            SubstitutionMethod substitutionMethod = (SubstitutionMethod) javaMethod;
            return substitutionMethod.getAnnotated().getDeclaringClass();
        }
        return javaMethod.getDeclaringClass();
    }

    private static ResolvedJavaType getOriginal(HostedType hostedType) {
        /* partially unwrap then traverse through substitutions to the original */
        ResolvedJavaType javaType = hostedType.getWrapped().getWrappedWithoutResolve();
        if (javaType instanceof SubstitutionType) {
            return ((SubstitutionType) javaType).getOriginal();
        } else if (javaType instanceof CustomSubstitutionType<?, ?>) {
            return ((CustomSubstitutionType<?, ?>) javaType).getOriginal();
        } else if (javaType instanceof LambdaSubstitutionType) {
            return ((LambdaSubstitutionType) javaType).getOriginal();
        } else if (javaType instanceof InjectedFieldsType) {
            return ((InjectedFieldsType) javaType).getOriginal();
        }
        return javaType;
    }
}

