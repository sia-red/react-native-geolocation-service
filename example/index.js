/**
 * @format
 */

import { AppRegistry } from 'react-native';
import App from './src/App';
import { name as appName } from './app.json';
import Geolocation from 'react-native-geolocation-service';

const locationCallback = async (location) => {
  console.log(location);
  Geolocation.clearWatchSubscriptions();
};

AppRegistry.registerHeadlessTask(
  'LocationHeadlessJsTask',
  () => locationCallback,
);

AppRegistry.registerComponent(appName, () => App);
