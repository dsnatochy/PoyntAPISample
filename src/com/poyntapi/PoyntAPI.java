package com.poyntapi;

import co.poynt.api.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.poyntapi.model.OrdersResponse;
import okhttp3.*;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.Security;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;

/**
 * Created by dennis on 2/9/17.
 */
public class PoyntAPI {

    private boolean DEBUG = true;
    private String apiEndpoint;

    // your application id starting with urn:aid
    private String applicationId;
    // private key downloaded from poynt.net
    private String privateKeyFile;

    private String accessToken;

    // business id and store id
    private String businessId;
    private String storeId;

    //terminal id
    private String storeDeviceId;

    // transaction action could be either SALE or AUTHORIZE
    // depending on merchant's processor settings
    private TransactionAction transactionAction;


    public PoyntAPI() throws Exception{

        /*
            Required properties in the config file:

            apiEndpoint=https://services.poynt.net
            applicationId=urn:aid:....
            privateKeyFile=src/privateKey.pem
            businessId=469e957c-xxxxx
            storeId=c2855b41-xxxx

         */
        File configFile = new File("src/config.properties");
        if (!configFile.exists()) {
            System.err.println("Config file does not exist");
            System.exit(1);
        }

        FileInputStream fis = new FileInputStream(configFile);
        Properties prop = new Properties();

        // load a properties file
        prop.load(fis);
        apiEndpoint = prop.getProperty("apiEndpoint");
        applicationId = prop.getProperty("applicationId");
        privateKeyFile = prop.getProperty("privateKeyFile");
        businessId = prop.getProperty("businessId");
        storeId = prop.getProperty("storeId");
        storeDeviceId = prop.getProperty("storeDeviceId");

        if (apiEndpoint == null || applicationId == null || privateKeyFile == null ||
                businessId == null || storeId == null || storeDeviceId == null){
            System.err.println("One of the required properties missing from the config file");
            System.exit(1);
        }

        accessToken = getAccessToken();
        transactionAction = getTransactionActionForStore();
    }

    private TransactionAction getTransactionActionForStore() throws Exception{
        String urlString = apiEndpoint + "/businesses/" + businessId + "/stores/" + storeId;
        String jsonResponse = doGet(urlString);
        ObjectMapper om = new ObjectMapper();
        Store store = om.readValue(jsonResponse, Store.class);
        if (store != null && store.getAttributes() != null){
            String purchaseAction = store.getAttributes().get("purchaseAction");
            if ("SALE".equals(purchaseAction)){
                return TransactionAction.SALE;
            }
        }
        // default to AUTHORIZE
        return TransactionAction.AUTHORIZE;
    }

    private String getJWT() throws Exception{
        File f = new File(privateKeyFile);
        if (!f.exists()){
            System.err.println("Private Key file not found");
            System.exit(1);
        }
        InputStreamReader isr = new InputStreamReader(new FileInputStream(f));

        PEMParser pemParser = new PEMParser(isr);
        Object object = pemParser.readObject();
        PEMKeyPair kp = (PEMKeyPair) object;
        Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());
        JcaPEMKeyConverter converter = new JcaPEMKeyConverter().setProvider("BC");
        RSAPrivateKey privateKey = (RSAPrivateKey) converter.getPrivateKey(kp.getPrivateKeyInfo());
        pemParser.close();


        // Create RSA-signer with the private key
        JWSSigner signer = new RSASSASigner(privateKey);

        // Prepare JWT with claims set
        JWTClaimsSet claimsSet = new JWTClaimsSet();
        claimsSet.setSubject(applicationId);
        claimsSet.setAudience(Arrays.asList(apiEndpoint));
        claimsSet.setIssuer(applicationId);
        claimsSet.setExpirationTime(new Date(new Date().getTime() + 360 * 1000));

        claimsSet.setIssueTime(new Date(new Date().getTime()));
        claimsSet.setJWTID(UUID.randomUUID().toString());

        SignedJWT signedJWT = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claimsSet);

        // Compute the RSA signature
        signedJWT.sign(signer);

        String s = signedJWT.serialize();
        System.out.println("JWT: " + s);
        return s;
    }

    private String getAccessToken() throws Exception{

        URL url = new URL(apiEndpoint + "/token");
        HttpsURLConnection conn = (HttpsURLConnection) url.openConnection();
        conn.setDoOutput(true);
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        conn.setRequestProperty("api-version", "1.2");

        String postData = "grantType=urn:ietf:params:oauth:grant-type:jwt-bearer";
        postData += "&assertion=" + getJWT();
        OutputStream os = conn.getOutputStream();
        os.write(postData.getBytes());
        os.flush();

        if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
            throw new RuntimeException("Failed : HTTP error code : "
                    + conn.getResponseCode());
        }

        BufferedReader br = new BufferedReader(new InputStreamReader((conn.getInputStream())));
        String response  = br.readLine();
        conn.disconnect();

        ObjectMapper mapper = new ObjectMapper();
        Map<String,String> map  = mapper.readValue(response, Map.class);
        return map.get("accessToken");
    }

    private String doGet(String urlString) throws Exception{
        URL url = new URL(urlString);
        OkHttpClient client = new OkHttpClient();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("api-version", "1.2")
                .addHeader("Poynt-Request-Id", UUID.randomUUID().toString())
                .addHeader("Authorization", "Bearer " + accessToken)
                .build();
        Response response = client.newCall(request).execute();
        String jsonResponse = response.body().string();

        if (DEBUG) System.out.println("response status code: " + response.code());
        if (DEBUG) System.out.println(jsonResponse);
        return jsonResponse;
    }

    public String doPost(String json, String urlString) throws Exception{
        URL url = new URL(urlString);
        OkHttpClient client = new OkHttpClient();

        MediaType JSON = MediaType.parse("application/json; charset=utf-8");
        RequestBody body = RequestBody.create(JSON, json);
        String requestId = UUID.randomUUID().toString();
        Request request = new Request.Builder()
                .url(url)
                .addHeader("api-version", "1.2")
                .addHeader("Poynt-Request-Id", requestId)
                .addHeader("Authorization", "Bearer " + accessToken)
                .post(body)
                .build();

        Response response  = client.newCall(request).execute();
        String responseString = response.body().string();
        if(DEBUG) System.out.println("create customer: " + responseString);
        return responseString;
    }

    public List<Catalog> getMerchantCatalogs() throws Exception{
        String urlString = apiEndpoint + "/businesses/"+ businessId +"/catalogs";
        ObjectMapper mapper = new ObjectMapper();

        Map<String,List<Catalog>> map  = mapper.readValue(doGet(urlString), new TypeReference<Map<String,List<Catalog>>>(){});
        if (DEBUG) System.out.println(map.get("catalogs"));
        return map.get("catalogs");
    }

    public Catalog getMerchantCatalog() throws Exception{
        return getMerchantCatalogs().get(0);
    }

    public List<Order> getOrdersForCustomer(String cardFirst6, String cardLast4, String cardExpirationMonth,
                                            String cardExpirationYear) throws Exception{
        String urlString = apiEndpoint + "/businesses/" + businessId + "/orders";
        urlString += "?cardNumberFirst6=" + cardFirst6;
        urlString += "&cardNumberLast4=" + cardLast4;
        urlString += "&cardExpirationMonth=" + cardExpirationMonth;
        urlString += "&cardExpirationYear=" + cardExpirationYear;

        ObjectMapper mapper = new ObjectMapper();
        OrdersResponse ordersResponse = mapper.readValue(doGet(urlString), OrdersResponse.class);
        return ordersResponse.getOrders();
    }

    public Order createOrder(Long customerId, String orderId) throws Exception{
        String endpoint = apiEndpoint + "/businesses/" + businessId + "/orders?process=true";

        long amount = 100l;
        float quantity = 10.0f;

        Order order = new Order();

        if (orderId != null){
            order.setId(UUID.fromString(orderId));
        }

        ClientContext context = new ClientContext();
        context.setBusinessId(UUID.fromString(businessId));
        context.setSource(TransactionSource.MOBILE);

        // This will send a push notification to the terminals in the store
        context.setStoreDeviceId(applicationId);
        context.setStoreId(UUID.fromString(storeId));
        order.setContext(context);

        List<OrderItem> items = new ArrayList<>();
        OrderItem item = new OrderItem();
        item.setName("Small coffee");
        item.setQuantity(quantity);
        item.setUnitOfMeasure(UnitOfMeasure.EACH);
        item.setSku("sku12348");
        item.setUnitPrice(amount);
        item.setStatus(OrderItemStatus.FULFILLED);
        item.setTax(0l);
/*

        Discount discount = new Discount();
        discount.setAmount(50l);
        discount.setCustomName("custom discount");
        item.setDiscounts(Arrays.asList(discount));
*/

        items.add(item);
        order.setItems(items);



        OrderStatuses orderStatuses = new OrderStatuses();
        orderStatuses.setStatus(OrderStatus.OPENED);
        order.setStatuses(orderStatuses);


        OrderAmounts orderAmounts = new OrderAmounts();
        orderAmounts.setCurrency("USD");
        orderAmounts.setSubTotal(new Long(amount*(long)quantity));

/*

        Discount orderLevelDiscount = new Discount();
        orderLevelDiscount.setAmount(-400l);
        orderLevelDiscount.setCustomName("Order level discount");
        order.setDiscounts(Arrays.asList(orderLevelDiscount));
*/

//        static discount. typically should be calculated as sum of item level + order level discounts
//        orderAmounts.setDiscountTotal(-900l);

        order.setAmounts(orderAmounts);

        String notes = "will pick up at 5pm";
        order.setNotes(notes);

        if (customerId != null) {
            order.setCustomerUserId(customerId);
        }

        ObjectMapper om = new ObjectMapper();

        String response = doPost(om.writeValueAsString(order), endpoint);

        Order newOrder = om.readValue(response, Order.class);
        if (DEBUG) System.out.println(newOrder);
        return newOrder;
    }


    public Long createCustomer(String firstName, String lastName, String imageUrl) throws Exception{
        String urlString = apiEndpoint + "/businesses/" + businessId + "/customers";
        Customer customer = new Customer();

        customer.setFirstName(firstName);
        customer.setLastName(lastName);

        Map<String, String> attributes = new HashMap<>();
        attributes.put("imageUrl", imageUrl);
        customer.setAttributes(attributes);

        ObjectMapper om = new ObjectMapper();
        String customerJson = om.writeValueAsString(customer);


        System.out.println(customerJson);

        String response = doPost(customerJson, urlString);

        Customer newCustomer = om.readValue(response, Customer.class);
        return newCustomer.getId();
    }

    public Customer createCustomerWithCard(String firstName, String lastName, String cardNumber, int expMonth, int expYear)
            throws Exception{
        String urlString = apiEndpoint + "/businesses/" + businessId + "/customers";

        Customer customer = new Customer();

        customer.setFirstName(firstName);
        customer.setLastName(lastName);

        Card card = new Card();
        card.setCardHolderFirstName(firstName);
        card.setCardHolderLastName(lastName);
        card.setNumber(cardNumber);
        card.setExpirationMonth(expMonth);
        card.setExpirationYear(expYear);

        customer.setCards(Collections.singletonList(card));

        ObjectMapper om = new ObjectMapper();
        String customerJson = om.writeValueAsString(customer);
        if (DEBUG) System.out.println("Customer request: " + customerJson);

        String response = doPost(customerJson, urlString);
        if (DEBUG) System.out.println("Customer Response: " + response);
        Customer newCustomer = om.readValue(response, Customer.class);
        return newCustomer;
    }

    public Business getBusinessByStoreDeviceId() throws Exception{
        String urlString = apiEndpoint + "/businesses/?storeDeviceId=" + storeDeviceId;
        String response = doGet(urlString);
        ObjectMapper om = new ObjectMapper();
        Business biz = om.readValue(response, Business.class);

        if (DEBUG) System.out.println(biz);

        return biz;
    }

    public List<StoreDevice> getStoreDevices() throws Exception{
        String urlString = apiEndpoint + "/businesses/" + businessId + "/stores/" + storeId + "/storeDevices";
        if(DEBUG) System.out.println("getStoreDevices url: " + urlString);
        String response = doGet(urlString);
        ObjectMapper om = new ObjectMapper();
        if(DEBUG) System.out.println("store: " + response);
        List<StoreDevice> allTerminals = om.readValue(response, new TypeReference<List<StoreDevice>>(){});
        List<StoreDevice> activeTerminals = new ArrayList<>();
        for (StoreDevice terminal : allTerminals){
            if (terminal.getStatus() == StoreDeviceStatus.ACTIVATED){
                activeTerminals.add(terminal);
            }
        }
        return activeTerminals;
    }

    public Catalog getStoreDeviceCatalog() throws Exception{
        String storeCatalogId = getStoreDevices().get(0).getCatalogId();
        String urlString = apiEndpoint + "/businesses/" + businessId + "/catalogs/" + storeCatalogId;
        if (DEBUG) System.out.println("store device catalog url: " + urlString);
        String response = doGet(urlString);
        ObjectMapper om = new ObjectMapper();
        return om.readValue(response, Catalog.class);
    }

    public Transaction createTransaction(String orderId) throws Exception{
        /*   Example JSON transaction
            {
                "action":"AUTHORIZE",
                "fundingSource": {
                    "type":"CREDIT_DEBIT",
                    "card":{
                        "number":"4111111111111111",
                        "expirationMonth":12,
                        "expirationYear":2020
                    },
                    "entryDetails":{
                       "customerPresenceStatus":"PRESENT",
                       "entryMode":"KEYED"
                    }
                 },
                 "references": [
                    {
                       "type": "CUSTOM",
                       "customType": "invoice",
                       "id": "123456789"
                    }
                 ],
                 "amounts": {
                    "currency":"USD",
                    "orderAmount":1287,
                    "cashbackAmount":0,
                    "transactionAmount":1287,
                    "tipAmount":0
                 },
                 "context": {
                    "transmissionAtLocal": "2014-09-11T23:14:44Z"
                 }
              }
         */

        String urlString = apiEndpoint + "/businesses/" + businessId + "/transactions";

        Transaction transaction = generateTransaction();

        if (orderId != null){
            TransactionReference orderReference = new TransactionReference();
            orderReference.setType(TransactionReferenceType.POYNT_ORDER);
            orderReference.setId(orderId.toString());
            if (transaction.getReferences() != null){
                transaction.getReferences().add(orderReference);
            }else {
                transaction.setReferences(Collections.singletonList(orderReference));
            }
        }



        ObjectMapper om = new ObjectMapper();
        String transactionJson = om.writeValueAsString(transaction);
        if (DEBUG) System.out.println("Transaction request: " + transactionJson);

        String response = doPost(transactionJson, urlString);
        if (DEBUG) System.out.println("Transaction Response: " + response);
        Transaction newTransaction = om.readValue(response, Transaction.class);
        return newTransaction;
    }

    private Transaction generateTransaction(){
        Transaction transaction = new Transaction();
        transaction.setAction(transactionAction);

        FundingSource fs = new FundingSource();
        fs.setType(FundingSourceType.CREDIT_DEBIT);
        Card card = new Card();
        card.setNumber("4111111111111111");
        card.setExpirationMonth(12);
        card.setExpirationYear(2020);
        card.setCardHolderFirstName("John");
        card.setCardHolderLastName("Smith");
        fs.setCard(card);
        FundingSourceEntryDetails entryDetails = new FundingSourceEntryDetails();
        entryDetails.setCustomerPresenceStatus(CustomerPresenceStatus.ECOMMERCE);
        entryDetails.setEntryMode(EntryMode.KEYED);
        fs.setEntryDetails(entryDetails);
        transaction.setFundingSource(fs);

        TransactionReference reference = new TransactionReference();
        reference.setType(TransactionReferenceType.CUSTOM);
        reference.setCustomType("CapOneCustomRef");
        reference.setId(UUID.randomUUID().toString());

/*
        UUID orderId = UUID.randomUUID();
        TransactionReference orderReference = new TransactionReference();
        orderReference.setType(TransactionReferenceType.POYNT_ORDER);
        orderReference.setId(orderId.toString());
        transaction.setReferences(Arrays.asList(reference, orderReference));
*/

        TransactionAmounts amounts = new TransactionAmounts();
        amounts.setCurrency("USD");
        amounts.setTransactionAmount(1000l);
        amounts.setOrderAmount(1000l);
        amounts.setTipAmount(0l);
        transaction.setAmounts(amounts);

        ClientContext context = new ClientContext();
        context.setTransmissionAtLocal(Calendar.getInstance());
        context.setBusinessId(UUID.fromString(businessId));
        context.setStoreId(UUID.fromString(storeId));
        context.setStoreDeviceId(storeDeviceId);
        transaction.setContext(context);

        return transaction;
    }

    public static void main(String[] args) {

        try {
            PoyntAPI api = new PoyntAPI();


/*            List<Catalog> catalogs = api.getMerchantCatalogs();
            if (catalogs != null && catalogs.size() > 0){
                Catalog catalog = catalogs.get(0);
                for (Category category : catalog.getCategories()){
                    System.out.println("category: " + category.getName());
                }
            }*/

            /*
             * Pull merchant catalog
             * For simplicity assuming that business has only one catalog
             */
            Catalog catalog = api.getMerchantCatalog();
            if (catalog != null){
                for (Category category : catalog.getCategories()){
                    System.out.println("category: " + category.getName());
                }
            }

            /*
             * Filter orders by customer's card
             */
            List<Order> orders = api.getOrdersForCustomer("411111","1111","12","2020");
            if (orders != null) {
                for (Order order : orders){
                    System.out.println("found order: " + order.getId());
                }
            }

            /*
             * Create new Customer
             */
            long customerId = api.createCustomer("John", "Smith", "https://pbs.twimg.com/media/ChfXfnMUoAAmQl5.jpg");


            /*
             * Create customer with card info
             */
            //Customer customerWithCard = api.createCustomerWithCard("John", "Smith", "4111111111111111", 1, 2020);


            /*
             * Look up business by terminal (storeDevice) id
             */
            Business business = api.getBusinessByStoreDeviceId();


            String orderId = UUID.randomUUID().toString();
            /*
             * Create a transaction by posting card details to Poynt server
             */
            Transaction transaction = api.createTransaction(orderId);

            /*
             * Create and push new order to the store
             */
            Order newOrder = api.createOrder(customerId, orderId);

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
