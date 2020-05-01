package fr.pierrezemb.recordstore.auth;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.clevercloud.biscuit.error.Error;
import io.vavr.control.Either;
import java.util.Collections;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BiscuitManagerTest {

  private static final String KEY = "3A8621F1847F19D6DAEAB5465CE8D3908B91C66FB9AF380D508FCF9253458907";
  BiscuitManager biscuitManager;

  @BeforeAll
  public void setup() {
    this.biscuitManager = new BiscuitManager(KEY);
  }

  @Test
  public void create() {
    byte[] sealed = this.biscuitManager.create("my-tenant", Collections.emptyList());
    Either<Error, Void> res = this.biscuitManager.checkTenant("my-tenant", sealed);
    assertFalse(res.isLeft());

    // should fail
    Either<Error, Void> res2 = this.biscuitManager.checkTenant("my-tedsanant", sealed);
    assertTrue(res2.isLeft());
  }
}
