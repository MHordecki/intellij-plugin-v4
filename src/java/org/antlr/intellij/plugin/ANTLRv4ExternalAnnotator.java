package org.antlr.intellij.plugin;

import com.intellij.lang.annotation.AnnotationHolder;
import com.intellij.lang.annotation.ExternalAnnotator;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiFile;
import org.antlr.intellij.plugin.psi.MyPsiUtils;
import org.antlr.runtime.ANTLRReaderStream;
import org.antlr.runtime.CommonToken;
import org.antlr.runtime.Token;
import org.antlr.v4.Tool;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.ANTLRToolListener;
import org.antlr.v4.tool.ErrorType;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.GrammarSemanticsMessage;
import org.antlr.v4.tool.GrammarSyntaxMessage;
import org.antlr.v4.tool.LeftRecursionCyclesMessage;
import org.antlr.v4.tool.Rule;
import org.antlr.v4.tool.ast.GrammarAST;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.stringtemplate.v4.ST;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class ANTLRv4ExternalAnnotator extends ExternalAnnotator<PsiFile, List<ANTLRv4ExternalAnnotator.Issue>> {
	public static final Logger LOG = Logger.getInstance("ANTLR ANTLRv4ExternalAnnotator");

	public static class Issue {
		String annotation;
		List<Token> offendingTokens = new ArrayList<Token>();
		ANTLRMessage msg;
		public Issue(ANTLRMessage msg) { this.msg = msg; }
	}

	// can't use instance var as only 1 instance

	/** Called first; return file; idea 12*/
	@Nullable
	public PsiFile collectionInformation(@NotNull PsiFile file) {
		return file;
	}

	/** Called 2nd; run antlr on file */
	@Nullable
	@Override
	public List<ANTLRv4ExternalAnnotator.Issue> doAnnotate(final PsiFile file) {
		String fileContents = file.getText();
		final List<ANTLRv4ExternalAnnotator.Issue> issues = new ArrayList<Issue>();
		final Tool antlr = new Tool();
		// getContainingDirectory() must be identified as a read operation on file system
		ApplicationManager.getApplication().runReadAction(new Runnable() {
			@Override
			public void run() {
				antlr.libDirectory = file.getContainingDirectory().toString();
			}
		});

		final FindVocabFileRunnable findVocabAction = new FindVocabFileRunnable(file);
		ApplicationManager.getApplication().runReadAction(findVocabAction);
		if ( findVocabAction.vocabName!=null ) { // need to generate other file?
			// for now, just turn off undef token warnings
		}

		antlr.addListener(new ANTLRToolListener() {
			@Override
			public void info(String msg) {
			}
			@Override
			public void error(ANTLRMessage msg) {
				if ( (msg.getErrorType()!=ErrorType.IMPLICIT_TOKEN_DEFINITION&&
					  msg.getErrorType()!=ErrorType.IMPLICIT_STRING_DEFINITION) ||
					findVocabAction.vocabName==null )
				{
					issues.add(new Issue(msg));
				}
			}
			@Override
			public void warning(ANTLRMessage msg) {
				issues.add(new Issue(msg));
			}
		});
		try {
			StringReader sr = new StringReader(fileContents);
			ANTLRReaderStream in = new ANTLRReaderStream(sr);
			in.name = file.getName();
			GrammarRootAST ast = antlr.parse(file.getName(), in);
			if ( ast==null || ast.hasErrors ) return Collections.emptyList();
			Grammar g = antlr.createGrammar(ast);
			antlr.process(g, false);

			for (int i = 0; i < issues.size(); i++) {
				Issue issue = issues.get(i);
				processIssue(issue);
			}
		}
		catch (IOException ioe) {
			LOG.error("antlr can't process "+file.getName(), ioe);
		}
		return issues;
	}

	/** Called 3rd */
	@Override
	public void apply(@NotNull PsiFile file,
					  List<ANTLRv4ExternalAnnotator.Issue> issues,
					  @NotNull AnnotationHolder holder)
	{
		for (int i = 0; i < issues.size(); i++) {
			Issue issue = issues.get(i);
			for (int j = 0; j < issue.offendingTokens.size(); j++) {
				Token t = issue.offendingTokens.get(j);
				if ( t instanceof CommonToken ) {
					CommonToken ct = (CommonToken)t;
					int startIndex = ct.getStartIndex();
					int stopIndex = ct.getStopIndex();
					TextRange range = new TextRange(startIndex, stopIndex + 1);
					switch (issue.msg.getErrorType().severity) {
					case ERROR:
					case ERROR_ONE_OFF:
					case FATAL:
						holder.createErrorAnnotation(range, issue.annotation);
						break;

					case WARNING:
						holder.createWarningAnnotation(range, issue.annotation);
						break;

					case WARNING_ONE_OFF:
					case INFO:
						holder.createWeakWarningAnnotation(range, issue.annotation);

					default:
						break;
					}
				}
			}
		}
		super.apply(file, issues, holder);
	}

	public void processIssue(Issue issue) {
		Tool antlr = new Tool();
		if ( issue.msg instanceof GrammarSemanticsMessage ) {
			Token t = ((GrammarSemanticsMessage)issue.msg).offendingToken;
			issue.offendingTokens.add(t);
		}
		else if ( issue.msg instanceof LeftRecursionCyclesMessage ) {
			List<String> rulesToHighlight = new ArrayList<String>();
			LeftRecursionCyclesMessage lmsg = (LeftRecursionCyclesMessage)issue.msg;
			for (Collection<Rule> cycle : lmsg.cycles) {
				for (Rule r : cycle) {
					rulesToHighlight.add(r.name);
					GrammarAST nameNode = (GrammarAST)r.ast.getChild(0);
					issue.offendingTokens.add(nameNode.getToken());
//					Collection<RulesNode> ruless =
//						PsiTreeUtil.collectElementsOfType(file, new Class[]{RulesNode.class});
//					RulesNode rules =
//						(RulesNode)MyPsiUtils.findRuleSpecNode(r.name,
//															   (RulesNode)ruless.toArray()[0]);
//					PsiElement rule = MyPsiUtils.findRuleSpecNode(r.name, rules);
				}
			}
		}
		else if ( issue.msg instanceof GrammarSyntaxMessage ) {
			Token t = ((GrammarSyntaxMessage)issue.msg).offendingToken;
			issue.offendingTokens.add(t);
		}

		ST msgST = antlr.errMgr.getMessageTemplate(issue.msg);
		String outputMsg = msgST.render();
		if (antlr.errMgr.formatWantsSingleLineMessage()) {
			outputMsg = outputMsg.replace('\n', ' ');
		}
		issue.annotation = outputMsg;
	}

	protected static class FindVocabFileRunnable implements Runnable {
		public String vocabName;
		private final PsiFile file;

		public FindVocabFileRunnable(PsiFile file) {
			this.file = file;
		}

		@Override
		public void run() {
			vocabName = MyPsiUtils.findTokenVocabIfAny((ANTLRv4FileRoot) file);
		}
	}
}
