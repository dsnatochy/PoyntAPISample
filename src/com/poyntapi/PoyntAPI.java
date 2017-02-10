package com.poyntapi;

import co.poynt.api.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
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
import org.bouncycastle.util.*;

import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.Security;
import java.security.interfaces.RSAPrivateKey;
import java.util.*;
import java.util.Arrays;

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

        if (apiEndpoint == null || applicationId == null || privateKeyFile == null ||
                businessId == null || storeId == null){
            System.err.println("One of the required properties missing from the config file");
            System.exit(1);
        }

        accessToken = getAccessToken();
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

    public Order createOrder(Long customerId) throws Exception{
        String endpoint = apiEndpoint + "/businesses/" + businessId + "/orders";

        long amount = 100l;
        float quantity = 10.0f;

        Order order = new Order();

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

        Discount discount = new Discount();
        discount.setAmount(50l);
        discount.setCustomName("custom discount");

        item.setDiscounts(Arrays.asList(discount));

        items.add(item);
        order.setItems(items);



        OrderStatuses orderStatuses = new OrderStatuses();
        orderStatuses.setStatus(OrderStatus.OPENED);
        order.setStatuses(orderStatuses);


        OrderAmounts orderAmounts = new OrderAmounts();
        orderAmounts.setCurrency("USD");
        orderAmounts.setSubTotal(new Long(amount*(long)quantity));

        Discount orderLevelDiscount = new Discount();
        orderLevelDiscount.setAmount(-400l);
        orderLevelDiscount.setCustomName("Order level discount");
        order.setDiscounts(Arrays.asList(orderLevelDiscount));

        // static discount. typically should be calculated as sum of item level + order level discounts
        orderAmounts.setDiscountTotal(-900l);

        order.setAmounts(orderAmounts);

        String notes = "will pick up at 5pm";
        order.setNotes(notes);

        if (customerId != null) {
            order.setCustomerUserId(customerId);
        }

        ObjectMapper om = new ObjectMapper();

        String response = doPost(om.writeValueAsString(order), endpoint);

        Order newOrder = om.readValue(response, Order.class);
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
            List<Order> orders = api.getOrdersForCustomer("439341","9403","06","2017");
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
             * Create and push new order to the store
             */
            Order newOrder = api.createOrder(customerId);

            /*
             * Create a transaction by posting card details to Poynt server
             */
            //TODO Add sample

        } catch (Exception e) {
            e.printStackTrace();
        }

    }
}
