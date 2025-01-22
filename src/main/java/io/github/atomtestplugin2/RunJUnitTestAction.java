package io.github.atomtestplugin2;

import com.intellij.debugger.ui.breakpoints.JavaLineBreakpointType;
import com.intellij.execution.*;
import com.intellij.execution.executors.DefaultDebugExecutor;
import com.intellij.execution.junit.JUnitConfiguration;
import com.intellij.execution.junit.JUnitConfigurationType;
import com.intellij.execution.runners.ExecutionEnvironment;
import com.intellij.execution.runners.ExecutionEnvironmentBuilder;
import com.intellij.execution.runners.ProgramRunner;
import com.intellij.ide.util.TreeClassChooser;
import com.intellij.ide.util.TreeClassChooserFactory;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.LangDataKeys;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.WriteCommandAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.fileChooser.FileChooser;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.ModuleRootManager;
import com.intellij.openapi.ui.DialogBuilder;
import com.intellij.openapi.ui.DialogWrapper;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.rt.execution.junit.RepeatCount;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBScrollPane;
import com.intellij.ui.components.JBTextField;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.ui.JBUI;
import com.intellij.xdebugger.XDebuggerManager;
import com.intellij.xdebugger.XDebuggerUtil;
import com.intellij.xdebugger.breakpoints.XBreakpointManager;
import com.intellij.xdebugger.breakpoints.XLineBreakpoint;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.java.debugger.breakpoints.properties.JavaLineBreakpointProperties;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.io.IOException;
import java.util.List;
import java.util.*;

/**
 * @author zhang kangkang
 */
public class RunJUnitTestAction extends AnAction {
    @Override
    public void actionPerformed(AnActionEvent e) {

        Project project = e.getProject();
        if (project == null) {
            return;
        }

        PsiElement selectedElement = e.getData(LangDataKeys.PSI_ELEMENT);
        if (selectedElement == null) {
            Messages.showMessageDialog(project, "No element selected.", "Error", Messages.getErrorIcon());
            return;
        }

        if (!(selectedElement instanceof PsiMethod method)) {
            Messages.showMessageDialog(project, "No method selected.", "Error", Messages.getErrorIcon());
            return;
        }

        Module module = e.getData(LangDataKeys.MODULE);
        if (module == null) {
            Messages.showMessageDialog(project, "No module found.", "Error", Messages.getErrorIcon());
            return;
        }

        // 检查方法是否为 public
        if (!method.hasModifierProperty(PsiModifier.PUBLIC)) {
            Messages.showMessageDialog(
                project,
                "Selected method is not public. Please select a public method.",
                "Error",
                Messages.getErrorIcon()
            );
            return;
        }

        // 获取方法所在的类
        PsiClass containingClass = method.getContainingClass();
        if (containingClass == null) {
            Messages.showMessageDialog(
                project,
                "Method is not contained in a class.",
                "Error",
                Messages.getErrorIcon()
            );
            return;
        }

        JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(project);
        PsiClass dynamicBeanLoadingClass = javaPsiFacade.findClass(
            "io.github.atom.test.annonation.DynamicBeanLoading",
            GlobalSearchScope.allScope(project)
        );
        if (dynamicBeanLoadingClass == null) {
            Messages.showMessageDialog(
                project,
                "尚未引入lazmore-test依赖，请引入以下依赖后继续操作：\n<dependency>\n"
                    + "            <groupId>io.github.mybingk</groupId>\n"
                    + "            <artifactId>atom-test</artifactId>\n"
                    + "            <version>1.0.3</version>\n"
                    + "        </dependency>",
                "Error",
                Messages.getErrorIcon()
            );
            return;
        }

        // 获取类的包名
        PsiPackage psiPackage = null;
        if (containingClass.getContainingFile().getParent() != null) {
            psiPackage = JavaDirectoryService.getInstance().getPackage(containingClass.getContainingFile().getParent());
        }
        String packageName = psiPackage != null ? psiPackage.getQualifiedName() : "";
        PsiClass existTestClass = getTestClass(project, module, packageName, containingClass.getName());
        if (existTestClass == null) {
            // 创建弹框
            DialogBuilder dialogBuilder = new DialogBuilder(project);
            dialogBuilder.setTitle("Run JUnit Test");

            PsiParameterList parameterList = method.getParameterList();
            PsiParameter[] parameters = parameterList.getParameters();

            // 创建UI组件
            JPanel panel = new JPanel(new GridLayoutManager(4 + parameters.length, 3, JBUI.insets(10), -1, -1));

            // 选择mainClass
            JBLabel mainClassLabel = new JBLabel("选择项目启动类:");
            Box mainClassLabelBox = Box.createHorizontalBox();
            mainClassLabelBox.add(getRequireLabel());
            mainClassLabelBox.add(mainClassLabel);
            mainClassLabelBox.setPreferredSize(new Dimension(150, mainClassLabelBox.getPreferredSize().height));

            JTextField mainClassField = new JBTextField();
            mainClassField.setPreferredSize(new Dimension(300, mainClassField.getPreferredSize().height));
            JButton mainClassButton = new JButton("选择类");
            mainClassButton.addActionListener(event -> {
                TreeClassChooser chooser =
                    TreeClassChooserFactory.getInstance(project).createWithInnerClassesScopeChooser(
                        "请选择项目启动类，即带有SpringBootApplication注解的类",
                        GlobalSearchScope.allScope(project),
                        null,
                        null
                    );
                ApplicationManager.getApplication().invokeLater(() -> {
                    chooser.showDialog();
                    PsiClass mainClass = chooser.getSelected();
                    if (mainClass != null) {
                        mainClassField.setText(mainClass.getQualifiedName());
                    }
                });
            });

            // 选择properties文件
            JBLabel propertiesLabel = new JBLabel("选择测试属性文件（src/test/resources）:");
            Box propertiesLabelBox = Box.createHorizontalBox();
            propertiesLabelBox.add(getRequireLabel());
            propertiesLabelBox.add(propertiesLabel);
            propertiesLabelBox.setPreferredSize(new Dimension(150, propertiesLabelBox.getPreferredSize().height));

            JTextField propertiesField = new JBTextField();
            propertiesField.setPreferredSize(new Dimension(300, propertiesField.getPreferredSize().height));
            JButton propertiesButton = new JButton("选择文件");
            propertiesButton.addActionListener(event -> {
                FileChooserDescriptor propertiesFileDescriptor =
                    FileChooserDescriptorFactory.createSingleFileDescriptor();
                propertiesFileDescriptor.withFileFilter(virtualFile -> {
                    String extension = virtualFile.getExtension();
                    return "yml".equals(extension) || "yaml".equals(extension) || "properties".equals(extension);
                });
                PsiDirectory testResourcesDirectory = getTestResourcesDirectory(module);
                if (testResourcesDirectory != null) {
                    propertiesFileDescriptor.withRoots(testResourcesDirectory.getVirtualFile());
                }
                VirtualFile[] propertiesFiles = FileChooser.chooseFiles(propertiesFileDescriptor, project, null);
                if (propertiesFiles.length > 0) {
                    String[] properties =
                        Arrays.stream(propertiesFiles).map(VirtualFile::getName).toArray(String[]::new);
                    propertiesField.setText(String.join(", ", properties));
                }
            });

            // 添加组件到面板
            addComponentToPanel(panel, mainClassLabelBox, 0, 0);
            addComponentToPanel(panel, mainClassField, 0, 1);
            addComponentToPanel(panel, mainClassButton, 0, 2);

            addComponentToPanel(panel, propertiesLabelBox, 1, 0);
            addComponentToPanel(panel, propertiesField, 1, 1);
            addComponentToPanel(panel, propertiesButton, 1, 2);

            int row = 2;

            // 创建参数标签
            JBLabel nacosLabel = new JBLabel("是否需要加载Nacos配置");
            Box nacosLabelBox = Box.createHorizontalBox();
            nacosLabelBox.add(nacosLabel);
            nacosLabelBox.setPreferredSize(new Dimension(150, nacosLabelBox.getPreferredSize().height));

            // 创建参数文本框
            JTextField nacosField = new JTextField("false");
            nacosField.setPreferredSize(new Dimension(300, nacosField.getPreferredSize().height));

            addComponentToPanel(panel, nacosLabelBox, row, 0);
            addComponentToPanel(panel, nacosField, row, 1);

            // 设置弹框内容
            dialogBuilder.setCenterPanel(panel);
            dialogBuilder.addOkAction();
            dialogBuilder.addCancelAction();

            // 显示弹框并获取用户输入
            if (dialogBuilder.show() == DialogWrapper.OK_EXIT_CODE) {
                String mainClassQualifiedName = mainClassField.getText();
                String propertiesFilesString = propertiesField.getText();
                String nacosString = nacosField.getText();

                // 解析用户输入
                PsiClass mainClass = JavaPsiFacade.getInstance(project)
                    .findClass(mainClassQualifiedName, GlobalSearchScope.allScope(project));
                if (mainClass == null) {
                    Messages.showMessageDialog(project, "请选择项目启动类", "Error", Messages.getErrorIcon());
                    return;
                }
                if (propertiesFilesString == null) {
                    Messages.showMessageDialog(project, "请选择测试属性文件", "Error", Messages.getErrorIcon());
                    return;
                }

                // 在 src/main/test 目录下查找或创建测试类
                PsiClass testClass = findOrCreateTestClass(
                    project,
                    module,
                    packageName,
                    containingClass.getName(),
                    mainClass,
                    propertiesFilesString,
                    nacosString
                );
                if (testClass == null) {
                    Messages.showMessageDialog(project, "创建测试类失败", "Error", Messages.getErrorIcon());
                    return;
                }

                runTestMethod(method, project, testClass, containingClass, module);

            }
            return;
        }

        PsiMethod existTestMethod = getTestMethod(existTestClass, method);
        if (existTestMethod == null) {
            runTestMethod(method, project, existTestClass, containingClass, module);
            return;
        }

        setBreakpointAtFirstLine(project, method);
        // 运行JUnit测试用例
        RunnerAndConfigurationSettings configurationSettings =
            createJUnitConfiguration(existTestMethod, project, module);
        runConfiguration(configurationSettings, project);
    }

    private void runTestMethod(PsiMethod method,
                               Project project,
                               PsiClass existTestClass,
                               PsiClass containingClass,
                               Module module) {
        // 创建弹框
        DialogBuilder dialogBuilder = new DialogBuilder(project);
        dialogBuilder.setTitle("Run JUnit Test");

        PsiParameterList parameterList = method.getParameterList();
        PsiParameter[] parameters = parameterList.getParameters();

        // 创建UI组件
        JPanel panel = new JPanel(new GridLayoutManager(parameters.length + 1, 3, new Insets(10, 10, 10, 10), -1, -1));

        int row = 0;
        List<JTextComponent> paramTextFieldList = new ArrayList<>();
        for (PsiParameter parameter : parameters) {
            String paramName = parameter.getName();
            PsiType paramType = parameter.getType();

            // 创建参数标签
            JBLabel paramLabel = new JBLabel(paramName + " (" + paramType.getPresentableText() + "):");
            Box paramLabelBox = Box.createHorizontalBox();
            paramLabelBox.add(getRequireLabel());
            paramLabelBox.add(paramLabel);
            paramLabelBox.setPreferredSize(new Dimension(150, paramLabelBox.getPreferredSize().height));

            if (isPrimitiveType(paramType)) {

                // 创建参数文本框
                JTextField paramField = new JTextField();
                paramField.setName(paramName);
                paramTextFieldList.add(paramField);
                paramField.setPreferredSize(new Dimension(300, paramField.getPreferredSize().height));
                addComponentToPanel(panel, paramLabelBox, row, 0);
                addComponentToPanel(panel, paramField, row, 1);
            } else {

                // 添加一个大的文本框让用户输入 JSON 格式的字符串
                JTextArea jsonTextArea = new JTextArea();
                jsonTextArea.setName(paramName);
                jsonTextArea.setLineWrap(true);
                jsonTextArea.setWrapStyleWord(true);
                paramTextFieldList.add(jsonTextArea);
                JBScrollPane scrollPane = new JBScrollPane(jsonTextArea);
                scrollPane.setPreferredSize(new Dimension(300, 100));

                // 生成占位符 JSON 并设置为初始文本
                String placeholderJson = generatePlaceholderJsonFromPsiType(paramType);
                jsonTextArea.setText(placeholderJson);

                addComponentToPanel(panel, paramLabel, row, 0);
                addComponentToPanel(panel, scrollPane, row, 1);
            }
            row++;
        }

        // 设置弹框内容
        dialogBuilder.setCenterPanel(panel);
        dialogBuilder.addOkAction();
        dialogBuilder.addCancelAction();

        // 显示弹框并获取用户输入
        if (dialogBuilder.show() == DialogWrapper.OK_EXIT_CODE) {

            for (JTextComponent paramTextField : paramTextFieldList) {
                if (paramTextField.getText() == null || paramTextField.getText().trim().isEmpty()) {
                    Messages.showMessageDialog(
                        project,
                        "请输入参数：" + paramTextField.getName(),
                        "Error",
                        Messages.getErrorIcon()
                    );
                    return;
                }
            }

            // 在测试类中查找或创建测试方法
            PsiMethod testMethod =
                findOrCreateTestMethod(existTestClass, method, containingClass, paramTextFieldList);
            if (testMethod == null) {
                Messages.showMessageDialog(project, "创建测试方法失败", "Error", Messages.getErrorIcon());
                return;
            }

            setBreakpointAtFirstLine(project, method);

            // 运行JUnit测试用例
            RunnerAndConfigurationSettings configurationSettings =
                createJUnitConfiguration(testMethod, project, module);
            runConfiguration(configurationSettings, project);
        }
    }

    private String generatePlaceholderJsonFromPsiType(PsiType paramType) {
        if (isListOrArrayType(paramType)) {
            return "[{}]";
        } else if (paramType instanceof PsiClassType classType) {
            PsiClass resolvedClass = classType.resolve();
            StringBuilder builder = new StringBuilder("{");
            if (resolvedClass != null) {
                for (PsiField field : resolvedClass.getFields()) {
                    if (!field.hasModifierProperty(PsiModifier.STATIC)) {
                        builder.append("\n").append("  ").append("\"").append(field.getName()).append("\":,");
                    }
                }
            }
            builder.append("\n}");
            return builder.toString();
        }
        return "{}";
    }

    public static void setBreakpointAtFirstLine(Project project, PsiMethod method) {
        // 获取方法的起始行号
        PsiFile psiFile = method.getContainingFile();
        Document document = PsiDocumentManager.getInstance(project).getDocument(psiFile);
        if (document == null) {
            return;
        }

        PsiCodeBlock methodBody = method.getBody();

        // 获取方法代码块的第一行内容
        PsiStatement firstStatement = methodBody.getStatements()[0];
        if (firstStatement == null) {
            return;
        }

        // 获取第一行的行号
        int startLine = document.getLineNumber(firstStatement.getTextRange().getStartOffset());

        // 获取当前活动的编辑器
        Editor editor = FileEditorManager.getInstance(project).getSelectedTextEditor();
        if (editor == null || !editor.getDocument().equals(document)) {
            return;
        }

        XBreakpointManager breakpointManager = XDebuggerManager.getInstance(project).getBreakpointManager();
        JavaLineBreakpointType lineBreakpointType =
            XDebuggerUtil.getInstance().findBreakpointType(JavaLineBreakpointType.class);

        @NotNull Collection<XLineBreakpoint<JavaLineBreakpointProperties>> existBreakPoint =
            breakpointManager.findBreakpointsAtLine(lineBreakpointType, psiFile.getVirtualFile(), startLine);
        if (existBreakPoint.isEmpty()) {
            breakpointManager.addLineBreakpoint(
                lineBreakpointType,
                psiFile.getVirtualFile().getUrl(),
                startLine,
                lineBreakpointType.createProperties()
            );
        }
    }

    private RunnerAndConfigurationSettings createJUnitConfiguration(PsiMethod method,
                                                                    Project project,
                                                                    com.intellij.openapi.module.Module module) {

        RunManager runManager = RunManager.getInstance(project);
        JUnitConfigurationType type = JUnitConfigurationType.getInstance();
        RunnerAndConfigurationSettings configurationSettings =
            runManager.createConfiguration(method.getName(), type.getConfigurationFactories()[0]);
        JUnitConfiguration configuration = (JUnitConfiguration) configurationSettings.getConfiguration();
        configuration.setModule(module);

        // 使用 PsiLocation.fromPsiElement 获取 Location<PsiMethod>
        Location<PsiMethod> methodLocation = PsiLocation.fromPsiElement(method);
        configuration.beMethodConfiguration(methodLocation);
        configuration.setRepeatMode(RepeatCount.UNLIMITED);

        return configurationSettings;
    }

    private void runConfiguration(RunnerAndConfigurationSettings configurationSettings, Project project) {

        try {

            // Obtain the Executor instance for JUnit
            Executor executor = DefaultDebugExecutor.getDebugExecutorInstance();

            ExecutionEnvironmentBuilder builder = ExecutionEnvironmentBuilder.create(executor, configurationSettings);
            ExecutionEnvironment environment = builder.build();
            ProgramRunner<?> runner = environment.getRunner();
            runner.execute(environment);
        } catch (Exception ex) {
            Messages.showMessageDialog(
                project,
                "Failed to run configuration: " + ex.getMessage(),
                "Error",
                Messages.getErrorIcon()
            );
        }
    }

    private PsiClass getTestClass(Project project,
                                  com.intellij.openapi.module.Module module,
                                  String packageName,
                                  String className) {
        // 获取 src/main/test 目录
        PsiDirectory testDirectory = getTestDirectory(module);
        if (testDirectory == null) {
            return null;
        }

        // 构建测试类的全限定名
        String testClassName = className + "Test";
        String testClassQualifiedName = packageName.isEmpty() ? testClassName : packageName + "." + testClassName;

        // 查找或创建测试类
        return JavaPsiFacade.getInstance(project)
            .findClass(testClassQualifiedName, GlobalSearchScope.moduleScope(module));
    }

    private PsiClass findOrCreateTestClass(Project project,
                                           com.intellij.openapi.module.Module module,
                                           String packageName,
                                           String className,
                                           PsiClass mainClass,
                                           String propertiesFiles,
                                           String nacosString) {
        // 获取 src/main/test 目录
        PsiDirectory testDirectory = getTestDirectory(module);
        if (testDirectory == null) {
            System.out.println("testDirectory is null");
            return null;
        }

        // 查找或创建测试类
        PsiClass testClass = getTestClass(project, module, packageName, className);
        if (testClass != null) {
            System.out.println("testDirectory is exist");
            return testClass;
        }
        // 构建测试类的全限定名
        String testClassName = className + "Test";
        String testClassQualifiedName = packageName.isEmpty() ? testClassName : packageName + "." + testClassName;
        JavaPsiFacade javaPsiFacade = JavaPsiFacade.getInstance(project);
        PsiElementFactory elementFactory = javaPsiFacade.getElementFactory();

        WriteCommandAction.runWriteCommandAction(
            project, (Computable<PsiClass>) () -> {

                PsiDirectory currentDirectory = getPsiDirectory(packageName, testDirectory);
                if (currentDirectory == null) {
                    System.out.println("currentDirectory is null");
                    return null;
                }

                PsiClass newClass = JavaDirectoryService.getInstance().createClass(currentDirectory, testClassName);
                if (!packageName.isEmpty()) {
                    PsiPackageStatement packageStatement = elementFactory.createPackageStatement(packageName);

                    // 检查是否已经存在包声明
                    PsiJavaFile javaFile = (PsiJavaFile) newClass.getContainingFile();
                    if (javaFile.getPackageStatement() == null) {
                        newClass.addBefore(packageStatement, newClass.getFirstChild());
                    }
                }

                PsiJavaFile javaFile = (PsiJavaFile) newClass.getContainingFile();
                Objects.requireNonNull(javaFile.getImportList()).add(elementFactory.createImportStatement(mainClass));
                String mainClassName = mainClass.getName();

                PsiClass dynamicBeanLoadingClass = javaPsiFacade.findClass(
                    "io.github.atom.test.annonation.DynamicBeanLoading",
                    GlobalSearchScope.allScope(project)
                );
                if (dynamicBeanLoadingClass != null) {
                    javaFile.getImportList().add(elementFactory.createImportStatement(dynamicBeanLoadingClass));
                }

                PsiAnnotation dynamicBeanLoadingAnnotation = elementFactory.createAnnotationFromText(
                    "@DynamicBeanLoading"
                        + "(\n\tmainClass = "
                        + mainClassName
                        + ".class, \n\tproperties = {\""
                        + propertiesFiles
                        + "\"}, "
                        + "\n\tnacosEnabled = "
                        + nacosString
                        + "\n)", newClass
                );
                Objects.requireNonNull(newClass.getModifierList())
                    .addBefore(dynamicBeanLoadingAnnotation, newClass.getModifierList().getFirstChild());

                PsiAnnotation dynamicResourceAnnotation =
                    elementFactory.createAnnotationFromText("@DynamicResource", null);
                PsiField dynamicResourceField = elementFactory.createFieldFromText(
                    "private "
                        + packageName
                        + "."
                        + className
                        + " "
                        + toLowerCaseFirstLetter(className)
                        + ";", null
                );
                Objects.requireNonNull(dynamicResourceField.getModifierList())
                    .addBefore(dynamicResourceAnnotation, dynamicResourceField.getModifierList().getFirstChild());
                newClass.add(dynamicResourceField);

                PsiClass dynamicResourceClass =
                    javaPsiFacade.findClass(
                        "io.github.atom.test.annonation.DynamicResource",
                        GlobalSearchScope.allScope(project)
                    );
                if (dynamicResourceClass != null) {
                    javaFile.getImportList().add(elementFactory.createImportStatement(dynamicResourceClass));
                }

                PsiClass baseClass = javaPsiFacade.findClass(
                    "io.github.atom.test.FastDynamicBeanLoadingTest",
                    GlobalSearchScope.allScope(project)
                );
                if (baseClass != null) {
                    PsiJavaCodeReferenceElement referenceElement =
                        elementFactory.createClassReferenceElement(baseClass);
                    Objects.requireNonNull(newClass.getExtendsList()).add(referenceElement);
                }

                return newClass;
            }
        );

        // 验证创建的类是否正确
        PsiClass createdClass = javaPsiFacade.findClass(testClassQualifiedName, GlobalSearchScope.allScope(project));
        if (createdClass == null || !createdClass.getName().equals(testClassName)) {
            Messages.showMessageDialog("Failed to create test class", "Error", Messages.getErrorIcon());
            return null;
        }

        return createdClass;
    }

    private static void commitDocment(PsiClass testClass) {
        // 提交所有文档更改
        PsiDocumentManager.getInstance(testClass.getProject()).commitAllDocuments();

        // 重新解析文件
        PsiFile psiFile = testClass.getContainingFile();
        if (psiFile != null) {
            Document document = psiFile.getViewProvider().getDocument();
            if (document != null) {
                PsiDocumentManager.getInstance(testClass.getProject()).commitDocument(document);
            }
        }
    }

    private static @Nullable PsiDirectory getPsiDirectory(String packageName, PsiDirectory testDirectory) {
        // 创建包目录结构
        PsiDirectory currentDirectory = testDirectory;
        if (!packageName.isEmpty()) {
            for (String packageComponent : packageName.split("\\.")) {
                PsiDirectory subDirectory = currentDirectory.findSubdirectory(packageComponent);
                if (subDirectory == null) {
                    try {
                        subDirectory = currentDirectory.createSubdirectory(packageComponent);
                    } catch (IncorrectOperationException e) {
                        e.printStackTrace();
                        return null;
                    }
                }
                currentDirectory = subDirectory;
            }
        }
        return currentDirectory;
    }

    private PsiDirectory getTestDirectory(com.intellij.openapi.module.Module module) {
        // 获取模块的 content root
        String contentRootPath = ModuleRootManager.getInstance(module).getContentRoots()[0].getPath();
        VirtualFile contentRoot = LocalFileSystem.getInstance().findFileByPath(contentRootPath);
        if (contentRoot == null) {
            return null;
        }

        // 查找或创建 src/main/test 目录
        VirtualFile testDir = contentRoot.findChild("src");
        if (testDir == null) {
            try {
                testDir = contentRoot.createChildDirectory(null, "src");
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        testDir = testDir.findChild("test");
        if (testDir == null) {
            try {
                testDir = contentRoot.findChild("src").createChildDirectory(null, "test");
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        testDir = testDir.findChild("java");
        if (testDir == null) {
            try {
                testDir = contentRoot.findChild("src").findChild("test").createChildDirectory(null, "java");
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        return PsiManager.getInstance(module.getProject()).findDirectory(testDir);
    }

    private PsiDirectory getTestResourcesDirectory(com.intellij.openapi.module.Module module) {
        // 获取模块的 content root
        String contentRootPath = ModuleRootManager.getInstance(module).getContentRoots()[0].getPath();
        VirtualFile contentRoot = LocalFileSystem.getInstance().findFileByPath(contentRootPath);
        if (contentRoot == null) {
            return null;
        }

        // 查找或创建 src/main/test 目录
        VirtualFile testDir = contentRoot.findChild("src");
        if (testDir == null) {
            try {
                testDir = contentRoot.createChildDirectory(null, "src");
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        testDir = testDir.findChild("test");
        if (testDir == null) {
            try {
                testDir = contentRoot.findChild("src").createChildDirectory(null, "test");
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        testDir = testDir.findChild("resources");
        if (testDir == null) {
            try {
                testDir = contentRoot.findChild("src").findChild("test").createChildDirectory(null, "resources");
            } catch (IOException e) {
                e.printStackTrace();
                return null;
            }
        }

        return PsiManager.getInstance(module.getProject()).findDirectory(testDir);
    }

    private PsiMethod getTestMethod(PsiClass testClass, PsiMethod originalMethod) {

        String testMethodName = "test" + capitalize(originalMethod.getName());
        Collection<PsiMethod> testMethods = PsiTreeUtil.findChildrenOfType(testClass, PsiMethod.class);

        // Filter methods by name
        for (PsiMethod method : testMethods) {
            if (method.getName().equals(testMethodName)) {

                // Check if the method already has content
                if (method.getBody() != null && !method.getBody().getText().trim().isEmpty()) {
                    return method;
                }

                return method;
            }
        }
        return null;
    }

    private PsiMethod findOrCreateTestMethod(PsiClass testClass,
                                             PsiMethod originalMethod,
                                             PsiClass containingClass,
                                             List<JTextComponent> paramTextFieldList) {

        String testMethodName = "test" + capitalize(originalMethod.getName());

        // Filter methods by name
        PsiMethod method = getTestMethod(testClass, originalMethod);
        if (method != null) {
            return method;
        }

        WriteCommandAction.runWriteCommandAction(
            testClass.getProject(), (Computable<PsiMethod>) () -> {
                // Create test method
                PsiMethod newTestMethod = PsiElementFactory.getInstance(testClass.getProject())
                    .createMethod(testMethodName, PsiTypes.voidType());
                newTestMethod.getModifierList().addAnnotation("org.junit.Test");

                newTestMethod.getThrowsList()
                    .add(PsiElementFactory.getInstance(testClass.getProject())
                        .createReferenceFromText("Exception", null));

                // Add method body to invoke the original method
                StringBuilder methodBody = new StringBuilder();
                methodBody.append("{\n");

                // Get the parameters of the original method
                PsiParameter[] parameters = originalMethod.getParameterList().getParameters();
                Map<String, String> paramMap = new HashMap<>();
                for (JTextComponent paramField : paramTextFieldList) {
                    paramMap.put(paramField.getName(), paramField.getText());
                }

                for (PsiParameter parameter : parameters) {
                    String paramName = parameter.getName();
                    PsiType paramType = parameter.getType();
                    String userInput = paramMap.get(paramName);
                    if (isPrimitiveType(paramType)) {
                        if (userInput != null) {
                            methodBody.append("    ")
                                .append(paramType.getPresentableText())
                                .append(" ")
                                .append(paramName)
                                .append(" = ")
                                .append(userInput)
                                .append(";\n");
                        }
                    } else {
                        String fullyQualifiedTypeName = paramType.getCanonicalText();
                        String genericClassName = getGenericClassName(paramType);
                        importClassIfNotExists(testClass, fullyQualifiedTypeName);
                        if (isListOrArrayType(paramType)) {
                            // Handle List or array type
                            methodBody.append("    ")
                                .append(fullyQualifiedTypeName)
                                .append(" ")
                                .append(paramName)
                                .append(" = com.alibaba.fastjson2.JSON.parseArray(\"")
                                .append(userInput.replace("\"", "\\\"").replace("\n", ""))
                                .append("\", ")
                                .append(genericClassName)
                                .append(".class);\n");
                        } else {
                            String outerClassName = getOuterClassName(fullyQualifiedTypeName);
                            // Handle other complex types
                            methodBody.append("    ")
                                .append(fullyQualifiedTypeName)
                                .append(" ")
                                .append(paramName)
                                .append(" = com.alibaba.fastjson2.JSON.parseObject(\"")
                                .append(userInput.replace("\"", "\\\"").replace("\n", ""))
                                .append("\", ")
                                .append(outerClassName)
                                .append(".class);\n");
                        }
                    }
                }

                methodBody.append("\t")
                    .append(toLowerCaseFirstLetter(containingClass.getName()))
                    .append(".")
                    .append(originalMethod.getName())
                    .append("(");
                for (int i = 0; i < parameters.length; i++) {
                    if (i > 0) {
                        methodBody.append(", ");
                    }
                    methodBody.append(parameters[i].getName());
                }
                methodBody.append(");\n");
                methodBody.append("}\n");

                // Add the method body to the test method
                Objects.requireNonNull(newTestMethod.getBody())
                    .replace(PsiElementFactory.getInstance(testClass.getProject())
                        .createCodeBlockFromText(methodBody.toString(), null));

                CodeStyleManager.getInstance(testClass.getProject()).reformat(testClass.add(newTestMethod));
                return newTestMethod;
            }
        );

        // 提交所有文档更改
        commitDocment(testClass);

        // 验证创建的方法是否正确
        PsiMethod verifiedMethod = testClass.findMethodsByName(testMethodName, false)[0];
        if (verifiedMethod == null || !verifiedMethod.getName().equals(testMethodName)) {
            Messages.showMessageDialog(
                testClass.getProject(),
                "Failed to create test method",
                "Error",
                Messages.getErrorIcon()
            );
            return null;
        }

        return verifiedMethod;
    }

    private String getGenericClassName(PsiType paramType) {
        if (paramType instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) paramType;
            PsiType[] parameters = classType.getParameters();
            if (parameters.length > 0) {
                return parameters[0].getCanonicalText();
            }
        }
        return null;
    }

    private boolean isListOrArrayType(PsiType paramType) {
        if (paramType instanceof PsiArrayType) {
            return true;
        }
        if (paramType instanceof PsiClassType) {
            PsiClassType classType = (PsiClassType) paramType;
            PsiClass resolvedClass = classType.resolve();
            if (resolvedClass != null) {
                return "java.util.List".equals(resolvedClass.getQualifiedName());
            }
        }
        return false;
    }

    private String getOuterClassName(String fullyQualifiedTypeName) {
        if (fullyQualifiedTypeName.contains("<")) {
            return fullyQualifiedTypeName.substring(0, fullyQualifiedTypeName.indexOf("<"));
        }
        return fullyQualifiedTypeName;
    }

    private void importClassIfNotExists(PsiClass testClass, String fullyQualifiedTypeName) {
        PsiJavaFile javaFile = (PsiJavaFile) testClass.getContainingFile();
        PsiImportList importList = javaFile.getImportList();
        if (importList == null) {
            return;
        }

        // 解析泛型信息
        List<String> classNamesToImport = new ArrayList<>();
        if (fullyQualifiedTypeName.contains("<")) {
            // 提取主类
            String mainClassName = fullyQualifiedTypeName.substring(0, fullyQualifiedTypeName.indexOf("<"));
            classNamesToImport.add(mainClassName);

            // 提取泛型中的实际类
            String genericPart = fullyQualifiedTypeName.substring(fullyQualifiedTypeName.indexOf("<") + 1, fullyQualifiedTypeName.lastIndexOf(">"));
            String[] genericClasses = genericPart.split(",");
            for (String genericClass : genericClasses) {
                genericClass = genericClass.trim();
                if (genericClass.contains("<")) {
                    // 递归处理嵌套泛型
                    importClassIfNotExists(testClass, genericClass);
                } else {
                    classNamesToImport.add(genericClass);
                }
            }
        } else {
            classNamesToImport.add(fullyQualifiedTypeName);
        }

        // 导入类
        PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(testClass.getProject());
        for (String className : classNamesToImport) {
            if (isClassAlreadyImported(importList, className)) {
                continue;
            }
            PsiClass classToImport = JavaPsiFacade.getInstance(testClass.getProject()).findClass(className, GlobalSearchScope.allScope(testClass.getProject()));
            if (classToImport != null) {
                PsiImportStatement importStatement = elementFactory.createImportStatement(classToImport);
                importList.add(importStatement);
            }
        }
    }

    private boolean isClassAlreadyImported(PsiImportList importList, String className) {
        PsiImportStatement[] importStatements = importList.getImportStatements();
        for (PsiImportStatement importStatement : importStatements) {
            if (className.equals(importStatement.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

    private String capitalize(String str) {

        return str.substring(0, 1).toUpperCase() + str.substring(1);
    }

    private boolean isPrimitiveType(PsiType paramType) {

        String canonicalText = paramType.getCanonicalText();
        return "String".equals(canonicalText)
            || "boolean".equals(canonicalText)
            || "byte".equals(canonicalText)
            || "char".equals(canonicalText)
            || "double".equals(canonicalText)
            || "float".equals(canonicalText)
            || "int".equals(canonicalText)
            || "long".equals(canonicalText)
            || "short".equals(canonicalText)
            || "Boolean".equals(canonicalText)
            || "Byte".equals(canonicalText)
            || "Char".equals(canonicalText)
            || "Double".equals(canonicalText)
            || "Float".equals(canonicalText)
            || "Integer".equals(canonicalText)
            || "Long".equals(canonicalText)
            || "Short".equals(canonicalText);
    }

    private String toLowerCaseFirstLetter(String str) {

        if (str == null || str.length() < 2) {
            return str;
        }
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

    private JBLabel getRequireLabel() {

        JBLabel requiredLabel = new JBLabel("*");
        requiredLabel.setForeground(JBColor.RED);
        return requiredLabel;
    }

    private void addComponentToPanel(JPanel panel, Component component, int row, int col) {

        panel.add(
            component, new GridConstraints(
                row,
                col,
                1,
                1,
                GridConstraints.ANCHOR_WEST,
                GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED,
                GridConstraints.SIZEPOLICY_FIXED,
                null,
                null,
                null
            )
        );
    }

}