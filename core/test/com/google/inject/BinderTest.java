/*
 * Copyright (C) 2007 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.inject;

import static com.google.common.truth.Truth.assertThat;
import static com.google.inject.Asserts.assertContains;
import static com.google.inject.Asserts.assertNotSerializable;
import static com.google.inject.Asserts.getDeclaringSourcePart;

import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.inject.internal.Annotations;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import com.google.inject.spi.Message;
import com.google.inject.util.Providers;
import java.io.IOException;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import junit.framework.TestCase;

/**
 * @author crazybob@google.com (Bob Lee)
 */
public class BinderTest extends TestCase {

  private final Logger loggerToWatch = Logger.getLogger(Guice.class.getName());

  private final List<LogRecord> logRecords = Lists.newArrayList();
  private final Handler fakeHandler =
      new Handler() {
        @Override
        public void publish(LogRecord logRecord) {
          logRecords.add(logRecord);
        }

        @Override
        public void flush() {}

        @Override
        public void close() throws SecurityException {}
      };

  Provider<Foo> fooProvider;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    loggerToWatch.addHandler(fakeHandler);
    TwoParts.injectedCount = 0;
  }

  @Override
  protected void tearDown() throws Exception {
    loggerToWatch.removeHandler(fakeHandler);
    super.tearDown();
  }

  public void testProviderFromBinder() {
    Guice.createInjector(
        new Module() {
          @Override
          public void configure(Binder binder) {
            fooProvider = binder.getProvider(Foo.class);

            try {
              fooProvider.get();
              fail();
            } catch (IllegalStateException e) {
              /* expected */
            }
          }
        });

    assertNotNull(fooProvider.get());
  }

  static class Foo {}

  public void testMissingBindings() {
    try {
      Guice.createInjector(
          // We put each binding in a separate module so the order of the error messages doesn't
          // depend on line numbers
          new AbstractModule() {
            @Override
            public void configure() {
              getProvider(Runnable.class);
            }
          },
          new AbstractModule() {
            @Override
            public void configure() {
              bind(Comparator.class);
            }
          },
          new AbstractModule() {
            @Override
            public void configure() {
              requireBinding(Key.get(new TypeLiteral<Callable<String>>() {}));
            }
          },
          new AbstractModule() {
            @Override
            public void configure() {
              bind(Date.class).annotatedWith(Names.named("date"));
            }
          });
      fail("Expected CreationException");
    } catch (CreationException e) {
      assertEquals(4, e.getErrorMessages().size());
      String segment1 = "No implementation for Runnable was bound.";
      String segment2 = "No implementation for Comparator was bound.";
      String segment3 = "No implementation for Callable<String> was bound.";
      String segment4 =
          String.format(
              "No implementation for Date annotated with @Named(%s) was bound.",
              Annotations.memberValueString("value", "date"));
      String sourceFileName = getDeclaringSourcePart(getClass());
      assertContains(
          e.getMessage(),
          segment1,
          sourceFileName,
          segment2,
          sourceFileName,
          segment3,
          sourceFileName,
          segment4,
          sourceFileName);
    }
  }

  public void testMissingDependency() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            public void configure() {
              bind(NeedsRunnable.class);
            }
          });
      fail("Expected CreationException");
    } catch (CreationException e) {
      assertEquals(1, e.getErrorMessages().size());
      assertContains(
          e.getMessage(),
          "No implementation for Runnable was bound.",
          ".runnable",
          "for field runnable",
          "at BinderTest$7.configure");
    }
  }

  static class NeedsRunnable {
    @Inject Runnable runnable;
  }

  public void testDanglingConstantBinding() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            public void configure() {
              bindConstant();
            }
          });
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(),
          "Missing constant value. Please call to(...).",
          "at BinderTest$8.configure");
    }
  }

  public void testRecursiveBinding() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            public void configure() {
              bind(Runnable.class).to(Runnable.class);
            }
          });
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(), "Binding points to itself.", "at BinderTest$9.configure");
    }
  }

  public void testBindingNullConstant() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            public void configure() {
              String none = null;
              bindConstant().annotatedWith(Names.named("nullOne")).to(none);
              bind(String.class).annotatedWith(Names.named("nullTwo")).toInstance(none);
            }
          });
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(),
          "1) Binding to null instances is not allowed. Use toProvider(() -> null)",
          "2) Binding to null instances is not allowed. Use toProvider(() -> null)");
    }
  }

  public void testToStringOnBinderApi() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            public void configure() {
              assertEquals("Binder", binder().toString());
              assertEquals("Provider<java.lang.Integer>", getProvider(Integer.class).toString());
              assertEquals(
                  "Provider<java.util.List<java.lang.String>>",
                  getProvider(Key.get(new TypeLiteral<List<String>>() {})).toString());

              assertEquals("BindingBuilder<java.lang.Integer>", bind(Integer.class).toString());
              assertEquals(
                  "BindingBuilder<java.lang.Integer>",
                  bind(Integer.class).annotatedWith(Names.named("a")).toString());
              assertEquals("ConstantBindingBuilder", bindConstant().toString());
              assertEquals(
                  "ConstantBindingBuilder",
                  bindConstant().annotatedWith(Names.named("b")).toString());
              assertEquals(
                  "AnnotatedElementBuilder",
                  binder().newPrivateBinder().expose(Integer.class).toString());
            }
          });
      fail();
    } catch (CreationException ignored) {
    }
  }

  public void testNothingIsSerializableInBinderApi() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            public void configure() {
              try {
                assertNotSerializable(binder());
                assertNotSerializable(getProvider(Integer.class));
                assertNotSerializable(getProvider(Key.get(new TypeLiteral<List<String>>() {})));
                assertNotSerializable(bind(Integer.class));
                assertNotSerializable(bind(Integer.class).annotatedWith(Names.named("a")));
                assertNotSerializable(bindConstant());
                assertNotSerializable(bindConstant().annotatedWith(Names.named("b")));
              } catch (IOException e) {
                fail(e.getMessage());
              }
            }
          });
      fail();
    } catch (CreationException ignored) {
    }
  }

  /**
   * Although {@code String[].class} isn't equal to {@code new GenericArrayTypeImpl(String.class)},
   * Guice should treat these two types interchangeably.
   */
  public void testArrayTypeCanonicalization() {
    final String[] strings = new String[] {"A"};
    final Integer[] integers = new Integer[] {1};

    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(String[].class).toInstance(strings);
                bind(new TypeLiteral<Integer[]>() {}).toInstance(integers);
              }
            });

    assertSame(integers, injector.getInstance(Key.get(new TypeLiteral<Integer[]>() {})));
    assertSame(integers, injector.getInstance(new Key<Integer[]>() {}));
    assertSame(integers, injector.getInstance(Integer[].class));
    assertSame(strings, injector.getInstance(Key.get(new TypeLiteral<String[]>() {})));
    assertSame(strings, injector.getInstance(new Key<String[]>() {}));
    assertSame(strings, injector.getInstance(String[].class));

    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            protected void configure() {
              bind(String[].class).toInstance(new String[] {"A"});
              bind(new TypeLiteral<String[]>() {}).toInstance(new String[] {"B"});
            }
          });
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(),
          "String[] was bound multiple times.",
          "1  : BinderTest$18.configure",
          "2  : BinderTest$18.configure");
      assertContains(expected.getMessage(), "1 error");
    }

    // passes because duplicates are ignored
    injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(String[].class).toInstance(strings);
                bind(new TypeLiteral<String[]>() {}).toInstance(strings);
              }
            });
    assertSame(strings, injector.getInstance(Key.get(new TypeLiteral<String[]>() {})));
    assertSame(strings, injector.getInstance(new Key<String[]>() {}));
    assertSame(strings, injector.getInstance(String[].class));
  }

  static class ParentModule extends AbstractModule {
    @Override
    protected void configure() {
      install(new FooModule());
      install(new BarModule());
    }
  }

  static class FooModule extends AbstractModule {
    @Override
    protected void configure() {
      install(new ConstantModule("foo"));
    }
  }

  static class BarModule extends AbstractModule {
    @Override
    protected void configure() {
      install(new ConstantModule("bar"));
    }
  }

  static class ConstantModule extends AbstractModule {
    private final String constant;

    ConstantModule(String constant) {
      this.constant = constant;
    }

    @Override
    protected void configure() {
      bind(String.class).toInstance(constant);
    }
  }

  /** Binding something to two different things should give an error. */
  public void testSettingBindingTwice() {
    try {
      Guice.createInjector(new ParentModule());
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(),
          "String was bound multiple times.",
          " BinderTest$ParentModule -> BinderTest$FooModule -> BinderTest$ConstantModule",
          " BinderTest$ParentModule -> BinderTest$BarModule -> BinderTest$ConstantModule");
      assertContains(expected.getMessage(), "1 error");
    }
  }

  /** Binding an @ImplementedBy thing to something else should also fail. */
  public void testSettingAtImplementedByTwice() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            protected void configure() {
              bind(HasImplementedBy1.class);
              bind(HasImplementedBy1.class).toInstance(new HasImplementedBy1() {});
            }
          });
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(),
          "BinderTest$HasImplementedBy1 was bound multiple times.",
          "1  : BinderTest$22.configure",
          "2  : BinderTest$HasImplementedBy1.class");
      assertContains(expected.getMessage(), "1 error");
    }
  }

  /** See issue 614, Problem One https://github.com/google/guice/issues/614 */
  public void testJitDependencyDoesntBlockOtherExplicitBindings() {
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(HasImplementedByThatNeedsAnotherImplementedBy.class);
                bind(HasImplementedBy1.class).toInstance(new HasImplementedBy1() {});
              }
            });
    injector.getAllBindings(); // just validate it doesn't throw.
    // Also validate that we're using the explicit (and not @ImplementedBy) implementation
    assertFalse(
        injector.getInstance(HasImplementedBy1.class) instanceof ImplementsHasImplementedBy1);
  }

  /** See issue 614, Problem Two https://github.com/google/guice/issues/id=614 */
  public void testJitDependencyCanUseExplicitDependencies() {
    Guice.createInjector(
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(HasImplementedByThatWantsExplicit.class);
            bind(JustAnInterface.class).toInstance(new JustAnInterface() {});
          }
        });
  }

  /**
   * Untargetted bindings should follow @ImplementedBy and @ProvidedBy annotations if they exist.
   * Otherwise the class should be constructed directly.
   */
  public void testUntargettedBinding() {
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(HasProvidedBy1.class);
                bind(HasImplementedBy1.class);
                bind(HasProvidedBy2.class);
                bind(HasImplementedBy2.class);
                bind(JustAClass.class);
              }
            });

    assertNotNull(injector.getInstance(HasProvidedBy1.class));
    assertNotNull(injector.getInstance(HasImplementedBy1.class));
    assertNotSame(HasProvidedBy2.class, injector.getInstance(HasProvidedBy2.class).getClass());
    assertNotSame(
        injector.getInstance(HasProvidedBy2.class), injector.getInstance(HasProvidedBy2.class));
    assertSame(
        ExtendsHasImplementedBy2.class, injector.getInstance(HasImplementedBy2.class).getClass());
    assertSame(JustAClass.class, injector.getInstance(JustAClass.class).getClass());
  }

  public void testPartialInjectorGetInstance() {
    Injector injector = Guice.createInjector();
    try {
      injector.getInstance(MissingParameter.class);
      fail();
    } catch (ConfigurationException expected) {
      assertContains(
          expected.getMessage(),
          "No injectable constructor for type BinderTest$NoInjectConstructor.",
          "at BinderTest$MissingParameter.<init>",
          "for 1st parameter noInjectConstructor");
    }
  }

  public void testUserReportedError() {
    final Message message = new Message(getClass(), "Whoops!");
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            protected void configure() {
              addError(message);
            }
          });
      fail();
    } catch (CreationException expected) {
      assertSame(message, Iterables.getOnlyElement(expected.getErrorMessages()));
    }
  }

  public void testUserReportedErrorsAreAlsoLogged() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            protected void configure() {
              addError(new Message("Whoops!", new IllegalArgumentException()));
            }
          });
      fail();
    } catch (CreationException expected) {
    }

    LogRecord logRecord = Iterables.getOnlyElement(this.logRecords);
    assertContains(
        logRecord.getMessage(),
        "An exception was caught and reported. Message: java.lang.IllegalArgumentException");
  }

  public void testBindingToProvider() {
    try {
      Guice.createInjector(
          new AbstractModule() {
            @Override
            protected void configure() {
              bind(new TypeLiteral<Provider<String>>() {}).toInstance(Providers.of("A"));
            }
          });
      fail();
    } catch (CreationException expected) {
      assertContains(
          expected.getMessage(),
          "Binding to Provider is not allowed.",
          "at BinderTest$28.configure");
    }
  }

  static class OuterCoreModule extends AbstractModule {
    @Override
    protected void configure() {
      install(new InnerCoreModule());
    }
  }

  static class InnerCoreModule extends AbstractModule {
    final Named red = Names.named("red");

    @Override
    protected void configure() {
      bind(AbstractModule.class).annotatedWith(red).toProvider(Providers.of(null));
      bind(Binder.class).annotatedWith(red).toProvider(Providers.of(null));
      bind(Binding.class).annotatedWith(red).toProvider(Providers.of(null));
      bind(Injector.class).annotatedWith(red).toProvider(Providers.of(null));
      bind(Key.class).annotatedWith(red).toProvider(Providers.of(null));
      bind(Module.class).annotatedWith(red).toProvider(Providers.of(null));
      bind(Provider.class).annotatedWith(red).toProvider(Providers.of(null));
      bind(Scope.class).annotatedWith(red).toProvider(Providers.of(null));
      bind(Stage.class).annotatedWith(red).toProvider(Providers.of(null));
      bind(TypeLiteral.class).annotatedWith(red).toProvider(Providers.of(null));
      bind(new TypeLiteral<Key<String>>() {}).toProvider(Providers.of(null));
    }
  }

  public void testCannotBindToGuiceTypes() {
    try {
      Guice.createInjector(new OuterCoreModule());
      fail();
    } catch (CreationException expected) {
      String methodLocation = "at BinderTest$InnerCoreModule.configure";
      String moduleChain = "installed by: BinderTest$OuterCoreModule -> BinderTest$InnerCoreModule";
      assertContains(
          expected.getMessage(),
          "Binding to core guice framework type is not allowed: AbstractModule.",
          methodLocation,
          moduleChain,
          "Binding to core guice framework type is not allowed: Binder.",
          methodLocation,
          moduleChain,
          "Binding to core guice framework type is not allowed: Binding.",
          methodLocation,
          moduleChain,
          "Binding to core guice framework type is not allowed: Injector.",
          methodLocation,
          moduleChain,
          "Binding to core guice framework type is not allowed: Key.",
          methodLocation,
          moduleChain,
          "Binding to core guice framework type is not allowed: Module.",
          methodLocation,
          moduleChain,
          "Binding to Provider is not allowed.",
          methodLocation,
          moduleChain,
          "Binding to core guice framework type is not allowed: Scope.",
          methodLocation,
          moduleChain,
          "Binding to core guice framework type is not allowed: Stage.",
          methodLocation,
          moduleChain,
          "Binding to core guice framework type is not allowed: TypeLiteral.",
          methodLocation,
          moduleChain,
          "Binding to core guice framework type is not allowed: Key.",
          methodLocation,
          moduleChain);
    }
  }

  static class MissingParameter {
    @Inject
    MissingParameter(NoInjectConstructor noInjectConstructor) {}
  }

  static class NoInjectConstructor {
    private NoInjectConstructor() {}
  }

  @ProvidedBy(HasProvidedBy1Provider.class)
  interface HasProvidedBy1 {}

  static class HasProvidedBy1Provider implements Provider<HasProvidedBy1> {
    @Override
    public HasProvidedBy1 get() {
      return new HasProvidedBy1() {};
    }
  }

  @ImplementedBy(ImplementsHasImplementedBy1.class)
  interface HasImplementedBy1 {}

  static class ImplementsHasImplementedBy1 implements HasImplementedBy1 {}

  @ProvidedBy(HasProvidedBy2Provider.class)
  static class HasProvidedBy2 {}

  static class HasProvidedBy2Provider implements Provider<HasProvidedBy2> {
    @Override
    public HasProvidedBy2 get() {
      return new HasProvidedBy2() {};
    }
  }

  @ImplementedBy(ExtendsHasImplementedBy2.class)
  static class HasImplementedBy2 {}

  static class ExtendsHasImplementedBy2 extends HasImplementedBy2 {}

  static class JustAClass {}

  @ImplementedBy(ImplementsHasImplementedByThatNeedsAnotherImplementedBy.class)
  static interface HasImplementedByThatNeedsAnotherImplementedBy {}

  static class ImplementsHasImplementedByThatNeedsAnotherImplementedBy
      implements HasImplementedByThatNeedsAnotherImplementedBy {
    @Inject
    ImplementsHasImplementedByThatNeedsAnotherImplementedBy(HasImplementedBy1 h1n1) {}
  }

  @ImplementedBy(ImplementsHasImplementedByThatWantsExplicit.class)
  static interface HasImplementedByThatWantsExplicit {}

  static class ImplementsHasImplementedByThatWantsExplicit
      implements HasImplementedByThatWantsExplicit {
    @Inject
    ImplementsHasImplementedByThatWantsExplicit(JustAnInterface jai) {}
  }

  static interface JustAnInterface {}

  //  public void testBindInterfaceWithoutImplementation() {
  //    Guice.createInjector(new AbstractModule() {
  //      protected void configure() {
  //        bind(Runnable.class);
  //      }
  //    }).getInstance(Runnable.class);
  //  }

  enum Roshambo {
    ROCK,
    SCISSORS,
    PAPER
  }

  public void testInjectRawProvider() {
    try {
      Guice.createInjector().getInstance(Provider.class);
      fail();
    } catch (ConfigurationException expected) {
      Asserts.assertContains(
          expected.getMessage(),
          "Cannot inject a Provider that has no type parameter",
          "while locating Provider");
    }
  }

  private static interface Part1 {
    String getStr();
  }

  private static interface Part2 {}

  private static class TwoParts implements Part1, Part2 {
    String str;
    static int injectedCount = 0;

    @Inject
    void inject(String str) {
      injectedCount++;
      this.str = str;
    }

    @Override
    public String getStr() {
      return str;
    }
  }

  public void testToInstanceWithDifferentTypesWorksAndDoesntInjectInstanceTwice() {
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                TwoParts parts = new TwoParts();
                bind(Part1.class).toInstance(parts);
                bind(Part2.class).toInstance(parts);
              }

              @Provides
              String provideStr() {
                return "foo";
              }
            });
    Part1 parts = injector.getInstance(Part1.class);
    assertThat(parts).isSameInstanceAs(injector.getInstance(Part2.class));
    assertThat(parts.getStr()).isEqualTo("foo");
    assertThat(TwoParts.injectedCount).isEqualTo(1);
  }

  @ImplementedBy(ImplementsHasImplementedByThatWantsExplicitClass.class)
  static interface HasImplementedByThatWantsExplicitClass {}

  static class ImplementsHasImplementedByThatWantsExplicitClass
      implements HasImplementedByThatWantsExplicitClass {
    JustAClass jac;

    @Inject
    ImplementsHasImplementedByThatWantsExplicitClass(JustAClass jac) {
      this.jac = jac;
    }
  }

  /**
   * See https://github.com/google/guice/pull/1650.
   *
   * <p>Test that {@code JustAClass} is not bound with JIT binding when {@code
   * bind(HasImplementedByThatWantsExplicitClass.class}} is called before {@code
   * bind(JustAClass.class)}.
   */
  public void testJitDependencyCanUseExplicitDependenciesInAnyOrder() {
    // The test passes if injector creation doesn't throw.
    Guice.createInjector(
        new AbstractModule() {
          @Override
          protected void configure() {
            bind(HasImplementedByThatWantsExplicitClass.class);
            bind(JustAClass.class);
          }
        });
  }

  static class AnotherClass extends JustAClass {}

  public void testJitDependencyCanUseExplicitDependenciesInAnyOrder_withSubclass() {
    Injector injector =
        Guice.createInjector(
            new AbstractModule() {
              @Override
              protected void configure() {
                bind(HasImplementedByThatWantsExplicitClass.class);
                bind(JustAClass.class).to(AnotherClass.class);
              }
            });
    ImplementsHasImplementedByThatWantsExplicitClass hasser =
        (ImplementsHasImplementedByThatWantsExplicitClass)
            injector.getInstance(HasImplementedByThatWantsExplicitClass.class);
    assertThat(hasser.jac).isInstanceOf(AnotherClass.class);
  }
}
