package com.github.otymko.dt.bsl.lsconnector;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Path;
import org.eclipse.emf.ecore.EObject;
import org.eclipse.emf.ecore.util.EcoreUtil;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.xtext.nodemodel.util.NodeModelUtils;
import org.eclipse.xtext.util.CancelIndicator;
import org.eclipse.xtext.validation.Check;
import org.eclipse.xtext.validation.CheckType;

import com._1c.g5.v8.dt.bsl.model.Module;
import com._1c.g5.v8.dt.bsl.resource.BslResource;
import com._1c.g5.v8.dt.bsl.validation.CustomValidationMessageAcceptor;
import com._1c.g5.v8.dt.bsl.validation.IExternalBslValidator;
import com.github.otymko.dt.bsl.lsconnector.util.BSLCommon;

public class BSLValidator implements IExternalBslValidator {

    @Override
    public boolean needValidation(EObject object) {
	return object instanceof Module;
    }

    @Override
    @Check(CheckType.EXPENSIVE)
    public void validate(EObject object, CustomValidationMessageAcceptor messageAcceptor, CancelIndicator monitor) {
	// попытка прервать
	if (monitor.isCanceled()) {
	    return;
	}

	var resource = (BslResource) ((Module) object).eResource();
	if (!resource.isDeepAnalysing()) {
	    return;
	}

	BSLPlugin.getPlugin().getLsService().getConnector()
		.runFutureTask(() -> validateFuture(object, messageAcceptor, monitor));
    }

    private void validateFuture(EObject object, CustomValidationMessageAcceptor messageAcceptor,
	    CancelIndicator monitor) {
	var plugin = BSLPlugin.getPlugin();
	var connector = plugin.getLsService().getConnector();

	if (!plugin.isRunningLS()) {
	    return;
	}

	var module = (Module) object;
	var node = NodeModelUtils.findActualNodeFor(module);
	var content = node.getText();
	if (content == null) {
	    content = "";
	}
	var document = new Document(content);
	var moduleFile = ResourcesPlugin.getWorkspace().getRoot()
		.getFile(new Path(EcoreUtil.getURI(module).toPlatformString(true)));
	var uri = BSLCommon.uri(moduleFile.getLocationURI());

	// Костыль при открытии, если на форме нет фокуса
	if (plugin.getWorkbenchParts().contains(uri.toString())) {
	    connector.textDocumentDidChange(uri, content);
	} else {
	    plugin.getWorkbenchParts().add(uri.toString());
	    connector.textDocumentDidOpen(uri, content);
	}

	// FIXME: иначе может быть NPE
	plugin.sleepCurrentThread(1000);
	// попытка прервать
	if (monitor.isCanceled()) {
	    return;
	}
	// если документ уже закрыт
	if (!plugin.getWorkbenchParts().contains(uri.toString())) {
	    return;
	}

	var diagnostics = connector.diagnostics(uri.toString());

	// попытка прервать
	if (monitor.isCanceled()) {
	    return;
	}

	diagnostics.forEach(diagnostic -> {
	    // попытка прервать
	    if (monitor.isCanceled()) {
		return;
	    }
	    acceptIssue(module, messageAcceptor, diagnostic, document);
	});
    }

    private void acceptIssue(Module module, CustomValidationMessageAcceptor messageAcceptor, Diagnostic diagnostic,
	    Document document) {
	int[] offsetParams;
	try {
	    offsetParams = BSLCommon.getOffsetByRange(diagnostic.getRange(), document);
	} catch (BadLocationException e) {
	    BSLPlugin.createErrorStatus(e.getMessage(), e);
	    return;
	}
	var severity = diagnostic.getSeverity();
	var message = "[BSL LS] " + diagnostic.getMessage();
	if (severity == DiagnosticSeverity.Error) {
	    messageAcceptor.acceptError(message, module, offsetParams[0], offsetParams[1], diagnostic.getCode());
	} else if (severity == DiagnosticSeverity.Warning) {
	    messageAcceptor.acceptWarning(message, module, offsetParams[0], offsetParams[1], diagnostic.getCode());
	} else {
	    messageAcceptor.acceptInfo(message, module, offsetParams[0], offsetParams[1], diagnostic.getCode());
	}

    }

}
