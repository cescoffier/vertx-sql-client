package io.reactiverse.myclient.tck;

import io.reactiverse.myclient.junit.MyRule;
import io.reactiverse.sqlclient.BinaryDataTypeEncodeTestBase;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.ClassRule;
import org.junit.runner.RunWith;

@RunWith(VertxUnitRunner.class)
public class MyBinaryDataTypeEncodeTest extends BinaryDataTypeEncodeTestBase {
  @ClassRule
  public static MyRule rule = new MyRule();

  @Override
  protected void initConnector() {
    connector = ClientConfig.CONNECT.connect(vertx, rule.options());
  }

  @Override
  protected String statement(String... parts) {
    return String.join("?", parts);
  }

  @Override
  public void testFloat4(TestContext ctx) {
    //TODO in MySQL query float type return double
    testEncodeGeneric(ctx, "test_float_4", Double.class, 3.4028235E38);
  }
}
