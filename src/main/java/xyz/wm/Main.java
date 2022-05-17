package xyz.wm;

import conflux.web3j.Account;
import conflux.web3j.Cfx;
import conflux.web3j.contract.ContractCall;
import conflux.web3j.contract.abi.DecodeUtil;
import conflux.web3j.contract.abi.TupleDecoder;
import conflux.web3j.response.Log;
import conflux.web3j.response.Receipt;
import conflux.web3j.types.Address;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.*;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.abi.datatypes.generated.Uint32;

import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

public class Main {
    private static final int NETID_TEST = 1;        // 测试网的 netid/chainid
    private static final int NETID_MAIN = 1029;     // 主网的 netid/chainid
    // The address should be: cfxtest:aak2s9c102jm75vts2afz9w3whkb37b45as2xhxu8z
    private static final String PRIVATE_KEY_TEST_1 = "24b287c62b28c8d8aa445fc7b2afae7bda03b40649a6a6480e548958f231f012";        // 发起交易的地址私钥
    private static final String PUBLIC_KEY_TEST_2 = "cfxtest:aat08667wnz9mtu0yw4vd4repyde4uv3yad1b2a0v3";       // 收款方地址（公钥）
    private static final String CONTRACT_ADDRESS_TEST = "cfxtest:ace6dtgfxwn1nfzrfd6z8w38paanean74epvkz5vd1";       // 合约地址
    private static final BigInteger GAS_PRICE = new BigInteger("1000000000", 10);
    // Gas Price，可以理解为燃气费率，固定为 1Gdrip 即 1e9，这个变量要方便配置，最好能不用重新部署地配置

    public static void main(String[] args) throws Exception {
        Cfx cfx = Cfx.create("https://test.confluxrpc.com");        // 这个实例化比较慢，最好在使用单实例
        System.out.println("cfx 初始化完毕");

        // 调用只读合约（这种方式应该用不上）
        Address contractAddress = new Address(CONTRACT_ADDRESS_TEST);       // 当地址以“cfx”或者“cfxtest”开头的时候不能加第二个参数
        ContractCall contract = new ContractCall(cfx, contractAddress);     // 实例化合约
        // 尝试从链上获取 tokenid 为 1 的 NFT 的 URI，需要的参数及其类型可以参考之前发给您的文档，所有参数需要使用org.web3j.abi.datatypes里对应的类型实例化之后传入
        // 最后别忘了调用sendAndGet()
        String uri = contract.call("uri", new Uint256(new BigInteger("1", 10))).sendAndGet();
        // 尽管返回值就是 String 类型但还是需要进行解码，注意传入的 class 并非 String
        uri = DecodeUtil.decode(uri, Utf8String.class);
        System.out.println("只读合约调用成功，返回值为：" + uri);


        // 调用可写合约，铸造一个新的 NFT 到 PUBLIC_KEY_TEST_2 这个地址
        Address recipient = new Address(PUBLIC_KEY_TEST_2);
        // 合约的发起方一定要使用私钥创建Account对象
        Account account = Account.create(cfx, PRIVATE_KEY_TEST_1);
        Account.Option option = new Account.Option();
        option.withGasPrice(GAS_PRICE);     // 一定要设置，否则影响合约执行速度
        // 下面第一行三个参数是固定的，分别是：上面的option，合约对象，函数名字
        String txHash = account.call(option, contractAddress, "mintSingle",
                // 下面几行是合约参数，参考合约文档/代码
                recipient.getABIAddress(),      // _to
                new Uint256(new BigInteger("1", 10)),       // _initialSupply
                new Uint32(new BigInteger("1", 10)),        // _itemType
                new Bool(true),     // _tradable
                new Utf8String("测试NFT"),        // _tag
                new Utf8String("测试NFT"));       // _name
        System.out.println("交易发送成功，txhash：" + txHash);

//        String txHash = "0x5b81c955e1ab3ca1e773f78305943c0dac17c0649adf4209b40f6bc55f5f0fe0";
        // 查询交易状态以及读取event
        int i = 1;
        while (true) {
            System.out.println("==========第"+ (i++) + "次查询==========");
            // 查询交易执行状态，获取receipt
            Optional<Receipt> receiptOptional = cfx.getTransactionReceipt(txHash).sendAndGet();
            if (receiptOptional.isEmpty()) {        // 如果为空则表示交易尚未执行
                System.out.println("合约未执行");
            } else {        // 如果交易已经被执行，则可以获取收据receipt
                Receipt receipt = receiptOptional.get();
                System.out.println("合约状态已获取");
                short status = receipt.getOutcomeStatus();      // 0 成功；其他失败
                System.out.println("OutcomeStatus: " + status);
                if (status == 0) {      // 如果交易执行成功（executed），继续获取确认状态
                    String blkHash = receipt.getBlockHash();        // 后面要用到区块哈希
                    BigInteger risk = cfx.getConfirmationRisk(blkHash).sendAndGet().get();      // 获取 risk 值
                    BigDecimal risk_decimal = new BigDecimal(risk).setScale(10, RoundingMode.DOWN);     // 为了后面的计算，转换成 BigDecimal，小数点后保留十位
                    BigInteger uint256_max = BigInteger.ONE.shiftLeft(256).subtract(BigInteger.ONE);        // 获取 uint256 类型的最大值
                    BigDecimal uint256_max_decimal = new BigDecimal(uint256_max);
                    BigDecimal risk_fractional = risk_decimal.divide(uint256_max_decimal, RoundingMode.DOWN);       // 计算真正的 risk 值，这个值表示交易所在区块可能被篡改的概率
                    System.out.println("Risk: " + risk_fractional.toString());
                    BigDecimal riskTolerance = BigDecimal.valueOf(1e-8);
                    if (risk_fractional.compareTo(riskTolerance) < 0) {       // 如果计算出来的 risk < 1e-8，则认为已经 confirmed
                        System.out.println("交易已确认");
                        // 从交易释放的 event log 中获取重要的数据，这些数据通常只能在链上生成，比如新的 tokenid
                        // 这个功能应该用不上
                        Log log = receipt.getLogs().get(0);
                        String logData = log.getData();
                        System.out.println(DecodeUtil.decode(logData.substring(0, 66), Uint256.class));
                        break;
                    }
                }
            }
            Thread.sleep(3000);
        }
    }
}