package io.github.ly1806620741.arthas.plugin;

import com.taobao.arthas.core.shell.cli.CliToken;
import com.taobao.arthas.core.shell.cli.Completion;
import com.taobao.arthas.core.shell.session.Session;
import demo.MathGame;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.lang.instrument.Instrumentation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

class MockCommandCompletionTest {

    @Test
    void completeShouldSuggestClassNameLikeWatch() {
        MockCommand command = new MockCommand();
        TestCompletion completion = completion("demo.MathG");

        command.complete(completion);

        Assertions.assertEquals("ame", completion.completedValue);
        Assertions.assertTrue(completion.appendSpace);
    }

    @Test
    void completeShouldSuggestMethodNameLikeWatch() {
        MockCommand command = new MockCommand();
        TestCompletion completion = completion(MathGame.class.getName(), "", "primeF");

        command.complete(completion);

        Assertions.assertEquals("actors", completion.completedValue);
        Assertions.assertTrue(completion.appendSpace);
    }

    private static TestCompletion completion(String... values) {
        Instrumentation instrumentation = Mockito.mock(Instrumentation.class);
        Mockito.when(instrumentation.getAllLoadedClasses()).thenReturn(new Class<?>[] { MathGame.class });
        Session session = Mockito.mock(Session.class);
        Mockito.when(session.getInstrumentation()).thenReturn(instrumentation);
        return new TestCompletion(session, values);
    }

    private static final class TestCompletion implements Completion {
        private final Session session;
        private final List<CliToken> tokens;
        private final List<String> completedCandidates = new ArrayList<String>();
        private String completedValue;
        private boolean appendSpace;

        private TestCompletion(Session session, String... values) {
            this.session = session;
            this.tokens = new ArrayList<CliToken>();
            for (String value : values) {
                this.tokens.add(new TestCliToken(value));
            }
        }

        @Override
        public Session session() {
            return session;
        }

        @Override
        public String rawLine() {
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < tokens.size(); i++) {
                if (i > 0) {
                    builder.append(' ');
                }
                builder.append(tokens.get(i).value());
            }
            return builder.toString();
        }

        @Override
        public List<CliToken> lineTokens() {
            return tokens;
        }

        @Override
        public void complete(List<String> candidates) {
            completedCandidates.clear();
            completedCandidates.addAll(candidates);
        }

        @Override
        public void complete(String value, boolean terminal) {
            this.completedValue = value;
            this.appendSpace = terminal;
        }
    }

    private static final class TestCliToken implements CliToken {
        private final String value;

        private TestCliToken(String value) {
            this.value = value;
        }

        @Override
        public String value() {
            return value;
        }

        @Override
        public String raw() {
            return value;
        }

        @Override
        public boolean isText() {
            return true;
        }

        @Override
        public boolean isBlank() {
            return value == null || value.trim().isEmpty();
        }
    }
}


