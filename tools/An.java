import java.sql.*;
public class An {
  public static void main(String[] a) throws Exception {
    String url="jdbc:mysql://127.0.0.1:3306/mall?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
    try (Connection c=DriverManager.getConnection(url,"root","123456"); Statement s=c.createStatement()) {
      for (int round=1; round<=3; round++) {
        long t0=System.nanoTime();
        ResultSet r=s.executeQuery("SELECT COUNT(*) FROM product"); r.next();
        long count=System.nanoTime()-t0;
        t0=System.nanoTime();
        r=s.executeQuery("SELECT id,name,description,category,illustration,price,stock FROM product ORDER BY id LIMIT 24 OFFSET 0");
        int n=0; while(r.next()) n++;
        long page=System.nanoTime()-t0;
        t0=System.nanoTime();
        r=s.executeQuery("SELECT id,name,description,category,illustration,price,stock FROM product ORDER BY id LIMIT 24 OFFSET 4992");
        n=0; while(r.next()) n++;
        long deep=System.nanoTime()-t0;
        System.out.printf("第%d轮  COUNT(*)=%.2fms  首页=%.2fms  末页(OFFSET4992)=%.2fms%n",round,count/1e6,page/1e6,deep/1e6);
        Thread.sleep(500);
      }
    }
  }
}
