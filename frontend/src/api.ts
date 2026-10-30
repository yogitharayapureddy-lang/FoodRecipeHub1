
import axios from "axios";

// Render backend URL for production
// Local backend URL for development
const BASE_URL =
  import.meta.env.VITE_API_URL || "http://localhost:8081";

// Axios instance
const api = axios.create({
  baseURL: `${BASE_URL}/api`,
});

// Attach JWT token to authenticated requests
api.interceptors.request.use((config) => {
  const token = sessionStorage.getItem("jwtToken");

  if (token) {
    config.headers.set("Authorization", `Bearer ${token}`);
  }

  return config;
});

// REGISTER
export const register = (data: {
  name: string;
  email: string;
  password: string;
}) => api.post("/users/register", data);

// LOGIN
export const login = async (data: {
  email: string;
  password: string;
}) => {
  const response = await api.post("/users/login", data);

  if (!response.data?.token || !response.data?.user) {
    throw new Error("Invalid login response");
  }

  sessionStorage.setItem("jwtToken", response.data.token);

  return response;
};

// UPDATE RECIPE
export function updateMyRecipe(
  id: number,
  data: {
    name: string;
    description: string;
    ingredients: string;
    instructions: string;
    cuisine: string;
    category: string;
    dietary: string;
    imageUrl: string;
  }
) {
  return api.put(`/recipes/${id}`, data);
}

// LOGOUT
export const logout = async () => {
  sessionStorage.removeItem("jwtToken");
};

// CURRENT USER
export const getCurrentUser = () =>
  api.get("/users/me");

// GET ALL RECIPES
export const getRecipes = () =>
  api.get("/recipes");

// GET ONE RECIPE
export const getRecipe = (id: number) =>
  api.get(`/recipes/${id}`);

// ADD RECIPE
export const addRecipe = (data: object) =>
  api.post("/recipes", data);

// RATE RECIPE
export const rateRecipe = (id: number, rating: number) =>
  api.post(`/recipes/${id}/rate`, null, {
    params: { rating },
  });

// GET MY RATINGS
export const getMyRatings = () =>
  api.get("/recipes/ratings/mine");

// REMOVE RATING
export const removeRating = (id: number) =>
  api.delete(`/recipes/${id}/rate`);

// SAVE RECIPE
export const saveRecipe = (id: number) =>
  api.post(`/recipes/${id}/save`);

// GET SAVED RECIPES
export const getSavedRecipes = () =>
  api.get("/recipes/saved");

// REMOVE SAVED RECIPE
export const removeSavedRecipe = (id: number) =>
  api.delete(`/recipes/${id}/save`);

// GET MY RECIPES
export const getMyRecipes = () =>
  api.get("/recipes/mine");

// DELETE MY RECIPE
export const deleteMyRecipe = (id: number) =>
  api.delete(`/recipes/${id}`);

// SEARCH RECIPES
export const searchRecipes = (keyword: string) =>
  api.get("/recipes/search", {
    params: { keyword },
  });

export default api;
