package it.jaschke.alexandria;

/**
 * Created by harsh on 1/10/2016.
 */
public class UtillProvider {
    public static boolean validateBarcode(String barcode) {
        int i, s = 0, t = 0;
        char[] digits = barcode.toCharArray();
        if (barcode.length() == 10 || barcode.length() == 13) {
            if (barcode.length() == 10) {
                for (i = 0; i < 10; i++) {
                    if (digits[i] == 'X' || digits[i] == 'x') {
                        t += 10;
                    } else {
                        t += Integer.parseInt(""+digits[i]);
                    }
                    s += t;
                }
                return (s % 11)==0;
            }
            else{
                for(i=0;i<13;i++){
                    int temp=i+1;
                    int multipliyer;
                    if(temp%2==0){
                        multipliyer=3;
                    }
                    else{
                        multipliyer=1;
                    }
                    s+=multipliyer*( Integer.parseInt(""+digits[i]) );
                }
                return s%10==0;

            }
        }
        return false;
    }

    public static String returnKey="BarcodeResponse";
}
